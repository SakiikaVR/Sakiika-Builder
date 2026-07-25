using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;

namespace SakiikaBuilder;

/// <summary>
/// Wraps the Rust engine (<c>sakiika.exe</c>). Everything the GUI does — listing
/// permissions, probing the toolchain, building, installing — is a call into that
/// binary, which keeps the GUI free of build logic.
/// </summary>
public sealed class Engine
{
    /// <summary>Raised for every line the engine writes, already decoded.</summary>
    public event Action<string, bool>? Output;

    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true
    };

    public string ExecutablePath { get; }

    public Engine()
    {
        ExecutablePath = Locate();
    }

    /// <summary>
    /// Looks beside the GUI first (that is how it ships), then walks up to the
    /// cargo output so a developer run works straight from the repo.
    /// </summary>
    private static string Locate()
    {
        var candidates = new List<string>();
        var baseDir = AppContext.BaseDirectory;
        candidates.Add(Path.Combine(baseDir, "sakiika.exe"));

        var dir = new DirectoryInfo(baseDir);
        for (var i = 0; i < 8 && dir is not null; i++, dir = dir.Parent)
        {
            candidates.Add(Path.Combine(dir.FullName, "target", "release", "sakiika.exe"));
            candidates.Add(Path.Combine(dir.FullName, "target", "debug", "sakiika.exe"));
        }

        foreach (var candidate in candidates)
        {
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        // Fall back to the expected location so the error message names a real path.
        return Path.Combine(baseDir, "sakiika.exe");
    }

    public bool IsAvailable => File.Exists(ExecutablePath);

    private ProcessStartInfo StartInfo(IEnumerable<string> args)
    {
        var info = new ProcessStartInfo
        {
            FileName = ExecutablePath,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            UseShellExecute = false,
            CreateNoWindow = true,
            // The engine emits UTF-8; without this the console code page mangles
            // every Japanese message on the way in.
            StandardOutputEncoding = new UTF8Encoding(false),
            StandardErrorEncoding = new UTF8Encoding(false)
        };
        foreach (var arg in args)
        {
            info.ArgumentList.Add(arg);
        }
        return info;
    }

    public sealed record RunResult(int ExitCode, List<string> StdoutLines, List<string> StderrLines)
    {
        public string Stdout => string.Join("\n", StdoutLines);
        public string Stderr => string.Join("\n", StderrLines);
        public bool Success => ExitCode == 0;
    }

    public async Task<RunResult> RunAsync(
        IEnumerable<string> args,
        bool echo = true,
        CancellationToken cancel = default)
    {
        if (!IsAvailable)
        {
            throw new FileNotFoundException(
                $"ビルドエンジンが見つかりません: {ExecutablePath}\n" +
                "sakiika.exe を SakiikaBuilder.exe と同じフォルダーに置いてください。");
        }

        var stdout = new List<string>();
        var stderr = new List<string>();

        using var process = new Process { StartInfo = StartInfo(args) };
        var doneOut = new TaskCompletionSource();
        var doneErr = new TaskCompletionSource();

        process.OutputDataReceived += (_, e) =>
        {
            if (e.Data is null) { doneOut.TrySetResult(); return; }
            stdout.Add(e.Data);
            if (echo) { Output?.Invoke(e.Data, false); }
        };
        process.ErrorDataReceived += (_, e) =>
        {
            if (e.Data is null) { doneErr.TrySetResult(); return; }
            stderr.Add(e.Data);
            if (echo) { Output?.Invoke(e.Data, true); }
        };

        process.Start();
        process.BeginOutputReadLine();
        process.BeginErrorReadLine();

        using var registration = cancel.Register(() =>
        {
            try { if (!process.HasExited) { process.Kill(entireProcessTree: true); } }
            catch { /* すでに終了している */ }
        });

        await process.WaitForExitAsync(CancellationToken.None).ConfigureAwait(false);
        // WaitForExit returning does not guarantee the readers have drained.
        await Task.WhenAny(Task.WhenAll(doneOut.Task, doneErr.Task), Task.Delay(2000))
            .ConfigureAwait(false);

        return new RunResult(process.ExitCode, stdout, stderr);
    }

    private static T? ParseJson<T>(string text) where T : class
    {
        foreach (var line in text.Split('\n'))
        {
            var trimmed = line.Trim();
            if (!trimmed.StartsWith('{')) { continue; }
            try
            {
                var parsed = JsonSerializer.Deserialize<T>(trimmed, JsonOptions);
                if (parsed is not null) { return parsed; }
            }
            catch (JsonException)
            {
                // Not the line we wanted; keep looking.
            }
        }
        return null;
    }

    public async Task<DoctorResult> DoctorAsync(int targetSdk, CancellationToken cancel = default)
    {
        var result = await RunAsync(
            new[] { "doctor", "--json", "--target-sdk", targetSdk.ToString() },
            echo: false, cancel).ConfigureAwait(false);
        return ParseJson<DoctorResult>(result.Stdout)
               ?? new DoctorResult { Ok = false, Error = result.Stderr.Length > 0 ? result.Stderr : "応答がありません" };
    }

    public async Task<List<PermissionInfo>> PermissionsAsync(CancellationToken cancel = default)
    {
        var result = await RunAsync(new[] { "permissions", "--json" }, echo: false, cancel)
            .ConfigureAwait(false);
        return ParseJson<PermissionList>(result.Stdout)?.Permissions ?? new List<PermissionInfo>();
    }

    public async Task<List<ModuleInfo>> ModulesAsync(CancellationToken cancel = default)
    {
        var result = await RunAsync(new[] { "modules", "--json" }, echo: false, cancel)
            .ConfigureAwait(false);
        return ParseJson<ModuleList>(result.Stdout)?.Modules ?? new List<ModuleInfo>();
    }

    public async Task<List<LevelInfo>> LevelsAsync(CancellationToken cancel = default)
    {
        var result = await RunAsync(new[] { "levels", "--json" }, echo: false, cancel)
            .ConfigureAwait(false);
        return ParseJson<LevelList>(result.Stdout)?.Levels ?? new List<LevelInfo>();
    }

    /// <summary>
    /// Builds from a project file. Progress arrives on <see cref="Output"/> as
    /// NDJSON; the returned value is the final "done" record.
    /// </summary>
    public async Task<BuildDone> BuildAsync(
        string projectPath,
        bool install,
        Action<string, string> onStep,
        Action<string> onLog,
        CancellationToken cancel = default)
    {
        var args = new List<string> { "build", projectPath, "--json" };
        if (install) { args.Add("--install"); }

        BuildDone? done = null;
        var errorLines = new List<string>();

        void Handle(string line, bool isError)
        {
            if (isError)
            {
                errorLines.Add(line);
                onLog(line);
                return;
            }
            var trimmed = line.Trim();
            if (!trimmed.StartsWith('{'))
            {
                if (trimmed.Length > 0) { onLog(trimmed); }
                return;
            }
            try
            {
                using var doc = JsonDocument.Parse(trimmed);
                var type = doc.RootElement.TryGetProperty("type", out var t) ? t.GetString() : null;
                switch (type)
                {
                    case "step":
                        onStep(
                            doc.RootElement.GetProperty("name").GetString() ?? "",
                            doc.RootElement.GetProperty("detail").GetString() ?? "");
                        break;
                    case "log":
                        onLog(doc.RootElement.GetProperty("line").GetString() ?? "");
                        break;
                    case "done":
                        done = JsonSerializer.Deserialize<BuildDone>(trimmed, JsonOptions);
                        break;
                }
            }
            catch (JsonException)
            {
                onLog(trimmed);
            }
        }

        Output += Handle;
        try
        {
            var result = await RunAsync(args, echo: true, cancel).ConfigureAwait(false);
            if (!result.Success)
            {
                var detail = errorLines.Count > 0
                    ? string.Join("\n", errorLines)
                    : "詳細な出力がありません";
                throw new InvalidOperationException(detail);
            }
            if (done is null)
            {
                throw new InvalidOperationException(
                    "ビルドは終了しましたが結果を受け取れませんでした。ログを確認してください。");
            }
            return done;
        }
        finally
        {
            Output -= Handle;
        }
    }

    public async Task InstallAsync(string apkPath, CancellationToken cancel = default)
    {
        var result = await RunAsync(new[] { "install", apkPath }, echo: true, cancel)
            .ConfigureAwait(false);
        if (!result.Success)
        {
            throw new InvalidOperationException(
                result.Stderr.Length > 0 ? result.Stderr : "インストールに失敗しました");
        }
    }
}
