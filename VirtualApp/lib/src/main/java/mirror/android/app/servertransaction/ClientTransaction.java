package mirror.android.app.servertransaction;

import android.os.IBinder;

import java.util.List;

import mirror.RefClass;
import mirror.RefObject;

/** Android 9+: activity lifecycle requests arrive from the system wrapped in a ClientTransaction. */
public class ClientTransaction {
    public static Class<?> TYPE = RefClass.load(ClientTransaction.class,
            "android.app.servertransaction.ClientTransaction");
    public static RefObject<List<Object>> mActivityCallbacks;
    /** Android 12+. */
    public static RefObject<IBinder> mActivityToken;
    /** Android 9-11. */
    public static RefObject<IBinder> mClient;
}
