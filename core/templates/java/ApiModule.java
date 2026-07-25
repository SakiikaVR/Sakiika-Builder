package __PKG__;

import org.json.JSONObject;

/**
 * One namespace of the JS bridge. {@code name()} is what the page writes —
 * a module named "ui" is reachable as {@code Android.ui.toast(...)}.
 */
public abstract class ApiModule {

    protected final MainActivity act;
    protected final Bridge bridge;

    protected ApiModule(Bridge bridge) {
        this.bridge = bridge;
        this.act = bridge.activity();
    }

    public abstract String name();

    /**
     * Methods the page may call. Used to build the JS facade and to give a
     * useful error for typos instead of a silent undefined.
     */
    public abstract String[] methods();

    /**
     * @param args never null — an empty object when the page passed nothing.
     * @return any Java value; {@link Jsonx#wrap} makes it JSON.
     */
    public abstract Object invoke(String method, JSONObject args) throws Exception;

    /** Called on Activity teardown so modules can drop listeners. */
    public void dispose() {
    }

    protected BridgeError unknown(String method) {
        return new BridgeError("unknown_method",
                name() + "." + method + " は存在しません");
    }
}
