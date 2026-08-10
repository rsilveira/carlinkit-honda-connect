package it.smg.hu.manager;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.widget.Toast;

import com.fujitsu_ten.displayaudio.ecncservice.IEcNcService;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrServiceCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.IModeMgrServiceSWKeyEventCallBack;
import com.fujitsu_ten.displayaudio.modemanagement.ModeMgrManager;
import com.fujitsu_ten.displayaudio.oom.OomManager;
import com.fujitsu_ten.displayaudio.statemanagement.IStateMgrServiceCallBack;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrChangeInfo;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrInfo;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrManager;
import com.fujitsu_ten.displayaudio.statemanagement.StateMgrServiceConst;
import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuService;
import com.fujitsu_ten.displayaudio.steeringmenuservice.service.ISteeringMenuServiceCallback;
import com.fujitsu_ten.displayaudio.whitelist.common.Constants;
import com.fujitsu_ten.displayaudio.whitelist.common.IWhiteList;
import com.fujitsu_ten.displayaudio.whitelist.common.ProcessControl;

import java.util.ArrayList;
import java.util.List;

import it.smg.hu.config.Settings;
import it.smg.libs.aasdk.messenger.ChannelId;
import it.smg.libs.common.Log;
import it.smg.hu.carlinkit.CarlinkitFileLog;

public class HondaConnectManager {

    public interface HondaListener {
        void onDayNightUpdate(boolean isNight);
        void onSteeringWheelKey(int keyType);
    }

    public static class SWMode {
        public static final String SW_SERVICE = "SW SERVICE";
//        public static final String MODEMGR_KEY_CALLBACK = "MODEMGR KEY CALLBACK";
    }

    public static class  AudioStreamType {
        public static final int ADA_NORMAL = 11;
        public static final int ADA_INTERRUPT = 12;
        public static final int ADA_INTERRUPT_LP = 13;
        public static final int ADA_INTERRUPT_VR = 14;
        public static final int ADA_INTERRUPT_NAVI = 15;
    }

    static class ModeMgrMode {
        public static final int REQUEST_MODE = Integer.parseInt("011111", 2); //31
        public static final int CONFIRM_MODE = Integer.parseInt("111", 2); //7
        public static final int NOTIFY_MODE = Integer.parseInt("11", 2); //3
        public static final int AUDIO_MODE = Integer.parseInt("01", 2); //1
    }

    private static final String TAG = "HondaConnectManager";
    private static final String ModeMgrService = "ModeMgrService";
    private static final String StateMgrService = "StateMgrService";

    private static HondaConnectManager instance_;

    private ModeMgrManager modeMgrManager_;
    private StateMgrManager stateMgrManager_;

    private IModeMgrServiceCallBack modeMgrServiceCallBack_;
    private IModeMgrServiceSWKeyEventCallBack modeMgrServiceSWKeyEventCallBack_;

    private IStateMgrServiceCallBack stateMgrServiceCallBack_;

    // SteeringWheel service
    private ISteeringMenuService steeringMenuServiceIface_;
    private final ServiceConnection steeringMenuServiceConnection_;
    private ISteeringMenuServiceCallback steeringMenuServiceCallback_;
    private boolean boundToSteeringMenuService_;

    // EcNc service
    private IEcNcService ecNcServiceIface_;
    private final ServiceConnection ecNcServiceConnection_;
    private boolean boundToEcNcService_;
    private boolean micVrStarted_;

    private final Context context_;
    private final Settings settings_;

    private boolean hasAudioFocus_;
    private ProcessControl pControl_;
//    private CountDownLatch waitCond_;

    private final Handler mainHandler_;
    private final List<HondaListener> listeners_ = new ArrayList<>();
    private Boolean isNight_ = null;

    private final IBinder.DeathRecipient steeringMenuServiceDeathRecipient_ = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(TAG, "Steering Menu Service binder died! Resetting and re-binding...");
            mainHandler_.post(() -> {
                unbindToWheelService();
                bindToWheelService();
            });
        }
    };

    private final IBinder.DeathRecipient ecNcServiceDeathRecipient_ = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.e(TAG, "EcNc Service binder died! Resetting and re-binding...");
            mainHandler_.post(() -> {
                unbindFromEcNcService();
                bindToEcNcService();
            });
        }
    };

    private static Context appContext_;

    public static void init(Context context){
        appContext_ = context.getApplicationContext();
        instance_ = new HondaConnectManager(appContext_);
    }

    public static HondaConnectManager instance(){
        if (instance_ == null) {
            synchronized (HondaConnectManager.class) {
                if (instance_ == null && appContext_ != null) {
                    instance_ = new HondaConnectManager(appContext_);
                }
            }
        }
        return instance_;
    }

    public void addListener(HondaListener listener) {
        if (listener == null) return;
        synchronized (listeners_) {
            if (!listeners_.contains(listener)) {
                listeners_.add(listener);
            }
        }
    }

    public void removeListener(HondaListener listener) {
        if (listener == null) return;
        synchronized (listeners_) {
            listeners_.remove(listener);
        }
    }

    @SuppressLint("WrongConstant")
    private HondaConnectManager(Context context){
        if (Log.isInfo()) Log.i(TAG, "init");
        context_ = context;
        settings_ = Settings.instance();
        hasAudioFocus_ = false;
        boundToEcNcService_ = false;
        micVrStarted_ = false;
        mainHandler_ = new Handler(Looper.getMainLooper());

        modeMgrManager_ = null;
        stateMgrManager_ = null;

        try {
            modeMgrManager_ = (ModeMgrManager) context.getSystemService(ModeMgrService);
        } catch (Throwable t) {
            Log.e(TAG, "Could not get ModeMgrService", t);
        }

        try {
            stateMgrManager_ = (StateMgrManager) context.getSystemService(StateMgrService);
        } catch (Throwable t) {
            Log.e(TAG, "Could not get StateMgrService", t);
        }

        steeringMenuServiceConnection_ = new ServiceConnection() {
            private static final String TAG = "HondaConnectManager-steeringServiceConnection";

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    CarlinkitFileLog.log(TAG, "wheel service CONNECTED: "
                            + (name == null ? "?" : name.flattenToShortString()));
                    boundToSteeringMenuService_ = true;
                    steeringMenuServiceIface_ = ISteeringMenuService.Stub.asInterface(service);

                    try {
                        service.linkToDeath(steeringMenuServiceDeathRecipient_, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to link death recipient to steeringMenuServiceIface", e);
                    }

                    // Toast removido: aparecia sobre o dialogo de permissao USB,
                    // atrapalhando a confirmacao. O indice fica no log.
                    if (Log.isDebug()) {
                        Log.d(TAG, "Wheel Service conectado (idx " + settings_.advanced.steeringWheelIdx() + ")");
                    }

                    registerSteeringMenuCallback();

                    if (pControl_ != null && (pControl_.authType != Constants.AUTH_TYPE_PREINSTALL || hasAudioFocus_)){
                        notifySteeringMenuDispMode(1);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onServiceConnected Wheel", t);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (Log.isVerbose()) Log.v(TAG, "Honda Wheel Service disconnected");
                try {
                    if (steeringMenuServiceIface_ != null) {
                        steeringMenuServiceIface_.asBinder().unlinkToDeath(steeringMenuServiceDeathRecipient_, 0);
                    }
                } catch (Throwable t) {}
                try {
                    if (modeMgrManager_ != null) {
                        modeMgrManager_.setImidConnectStatus(0);
                    }
                } catch (Throwable t) {}
                boundToSteeringMenuService_ = false;
                steeringMenuServiceIface_ = null;
                steeringMenuServiceCallback_ = null;
            }
        };

        ecNcServiceConnection_ = new ServiceConnection() {
            private static final String TAG = "HondaConnectManager-ecNcServiceConnection";

            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                try {
                    if (Log.isVerbose()) Log.v(TAG, "Honda EcNc Service connected");
                    boundToEcNcService_ = true;
                    ecNcServiceIface_ = IEcNcService.Stub.asInterface(service);
                    try {
                        service.linkToDeath(ecNcServiceDeathRecipient_, 0);
                    } catch (RemoteException e) {
                        Log.e(TAG, "Failed to link death recipient to ecNcServiceIface", e);
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Error in onServiceConnected EcNc", t);
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                if (Log.isVerbose()) Log.v(TAG, "Honda EcNc Service disconnected");
                try {
                    if (ecNcServiceIface_ != null) {
                        ecNcServiceIface_.asBinder().unlinkToDeath(ecNcServiceDeathRecipient_, 0);
                    }
                } catch (Throwable t) {}
                try {
                    if (modeMgrManager_ != null) {
                        modeMgrManager_.setImidConnectStatus(0);
                    }
                } catch (Throwable t) {}
                boundToEcNcService_ = false;
                ecNcServiceIface_ = null;
                micVrStarted_ = false; // force to false
            }
        };

        try {
            // A whitelist da central e indexada pelo package name. Usar o package real
            // em vez de uma constante permite que variantes com applicationId diferente
            // (ex.: modo Carlinkit) consultem a propria entrada no HondaPermissions.
            pControl_ = IWhiteList.getProcessControl(context_.getPackageName(), null);
            if (pControl_ != null) {
                Log.d(TAG, "ProcessControl [ ");
                Log.d(TAG, "appType= " + pControl_.appType);
                Log.d(TAG, "authType= " + pControl_.authType);
                Log.d(TAG, "lastMode= " + pControl_.lastMode);
                Log.d(TAG, "oomSetPerm= " + pControl_.oomSetPerm);
                Log.d(TAG, "result= " + pControl_.result);
                Log.d(TAG, "soundInterrupt= " + pControl_.soundInterrupt);
                Log.d(TAG, "soundInterruptMute= " + pControl_.soundInterruptMute);
                Log.d(TAG, "soundOut= " + pControl_.soundOut);
                Log.d(TAG, "videoOut= " + pControl_.videoOut);
                Log.d(TAG, "]");
            }
        } catch (Throwable t){
            Log.e(TAG, "process control error", t);
        }

    }

    public int mediaAudioStream(ChannelId audioChannel){
        if (pControl_ != null && pControl_.authType == Constants.AUTH_TYPE_PREINSTALL){
            switch (audioChannel) {
                case SPEECH_AUDIO:
                case SYSTEM_AUDIO:
                    return AudioStreamType.ADA_INTERRUPT_NAVI;
                case MEDIA_AUDIO:
                default:
                    return AudioStreamType.ADA_NORMAL;
            }
        }

        return AudioTrack.MODE_STREAM;
    }

    public void adjustPermission(){
        try {
            int ret = OomManager.getOomAdjustment(context_);
            if (Log.isDebug()) Log.d(TAG, "getOomAdjustment " + ret);
            ret = OomManager.setOomAdjustmentCoreServer(context_);
            if (Log.isDebug()) Log.d(TAG, "setOomAdjustment " + ret);
        } catch (Exception e){
            Log.e(TAG, "error in adjustPermission", e);
        }
    }

    public void requestAudioFocus(){
        if (pControl_ == null) {
            if (Log.isWarn()) Log.w(TAG, "requestAudioFocus -> pControl_ is null");
            return;
        }
        if (modeMgrManager_ == null) {
            if (Log.isWarn()) Log.w(TAG, "requestAudioFocus -> modeMgrManager_ is null");
            return;
        }
        if (Log.isDebug()) Log.d(TAG, "requestAudioFocus -> app with auth " + pControl_.authType);
        if (Log.isVerbose()) Log.v(TAG, "requestAudioFocus modeMgr audio hasAudioFocus= " + hasAudioFocus_);

        // Applies to any authType: THIRD_PARTY apps also have to announce to ModeMgr that
        // they took over the mode, otherwise the unit does not deliver steering wheel events.
        if (!hasAudioFocus_) {
            int idx = settings_.advanced.modeMgrAudioIdx();
            int ret;

            if (Log.isVerbose()) Log.v(TAG, "requestAudioFocus sendModeMgrOnReq idx= " + idx + ", mode= " + ModeMgrMode.REQUEST_MODE);
            try {
                ret = modeMgrManager_.sendModeMgrOnReq(idx, ModeMgrMode.REQUEST_MODE);
            } catch (Throwable t) {
                Log.e(TAG, "Error calling sendModeMgrOnReq", t);
                ret = -1;
            }
            if (Log.isVerbose()) Log.v(TAG, "requestAudioFocus sendModeMgrOnReq result= " + ret);

            if (Log.isDebug()) Log.d(TAG, "requestAudioFocus notifySteeringMenuDispMode");
            notifySteeringMenuDispMode(1);

            try {
                modeMgrManager_.setImidConnectStatus(1);
            } catch (Throwable t) {
                Log.w(TAG, "Could not set i-MID connect status", t);
            }

            hasAudioFocus_ = true;
        }
    }

    public void releaseAudioFocus(){
        if (pControl_ == null) {
            if (Log.isWarn()) Log.w(TAG, "releaseAudioFocus -> pControl_ is null");
            return;
        }
        if (modeMgrManager_ == null) {
            if (Log.isWarn()) Log.w(TAG, "releaseAudioFocus -> modeMgrManager_ is null");
            return;
        }
        if (Log.isDebug()) Log.d(TAG, "releaseAudioFocus -> app with auth " + pControl_.authType);
        if (Log.isVerbose()) Log.v(TAG, "releaseAudioFocus modeMgr audio hasAudioFocus= " + hasAudioFocus_);

        if (hasAudioFocus_) {
            int idx = settings_.advanced.modeMgrAudioIdx();
            int ret;

            try {
                if (Log.isDebug()) Log.d(TAG, "releaseAudioFocus -> resetting i-MID and Steering Display");
                modeMgrManager_.setImidConnectStatus(0);
                notifySteeringMenuDispMode(0);
            } catch (Throwable t) {
                Log.w(TAG, "Could not reset i-MID/Steering status", t);
            }

            if (Log.isVerbose())  Log.v(TAG, "releaseAudioFocus sendModeMgrOffReq idx= " + idx + ", state = " + ModeMgrMode.REQUEST_MODE);
            try {
                ret = modeMgrManager_.sendModeMgrOffReq(idx, ModeMgrMode.REQUEST_MODE);
            } catch (Throwable t) {
                Log.e(TAG, "Error calling sendModeMgrOffReq", t);
                ret = -1;
            }
            if (Log.isVerbose()) Log.v(TAG, "releaseAudioFocus sendModeMgrOffReq ret= " + ret);

            try {
                // Tells ModeMgr the app really is OFF. Without this the native FM radio does
                // not resume when leaving the app.
                modeMgrManager_.notifyModeMgrStatus(idx, 0);
            } catch (Throwable t) {
                Log.w(TAG, "Could not notify ModeMgr status OFF", t);
            }

            hasAudioFocus_ = false;
        }
    }

    public void increaseVolume(){
        if (Log.isVerbose()) Log.v(TAG, "increaseVolume");
        if (modeMgrManager_ != null) {
            try {
                modeMgrManager_.reqModeMgrSteeringVolCmd(true);
            } catch (Throwable t) {
                Log.e(TAG, "Error calling reqModeMgrSteeringVolCmd(true)", t);
            }
        }
    }

    public void decreaseVolume(){
        if (Log.isVerbose()) Log.v(TAG, "decreaseVolume");
        if (modeMgrManager_ != null) {
            try {
                modeMgrManager_.reqModeMgrSteeringVolCmd(false);
            } catch (Throwable t) {
                Log.e(TAG, "Error calling reqModeMgrSteeringVolCmd(false)", t);
            }
        }
    }

    // Used in onCreate
    public void initialize(){
        if (pControl_ != null) {
            if (Log.isDebug()) Log.d(TAG, "initialize -> app with auth " + pControl_.authType);
        } else {
            if (Log.isDebug()) Log.d(TAG, "initialize -> pControl_ is null");
        }

        bindToEcNcService();
        bindToWheelService();

    }

    // Used in onResume
    public void initAudioBinding(){
        if (pControl_ == null) {
            if (Log.isWarn()) Log.w(TAG, "initAudioBinding -> pControl_ is null");
            return;
        }
        if (Log.isDebug()) Log.d(TAG, "initAudioBinding -> app with auth " + pControl_.authType);

        checkAndReconnectServices();

        // Recuperacao pos deep-sleep: se um servico morreu, refaz o bind
        if (!boundToSteeringMenuService_) {
            Log.w(TAG, "initAudioBinding -> wheel service is not bound, rebinding");
            bindToWheelService();
        }
        if (!boundToEcNcService_) {
            bindToEcNcService();
        }

        // Registra todos os callbacks independente do authType
        registerModeMgrCallback();
        registerSteeringMenuCallback();
        registerStateMgrCallback();

        if (Log.isVerbose()) Log.v(TAG, "initAudioBinding -> hasAudioFocus= " + hasAudioFocus_);
        if (hasAudioFocus_){
            notifySteeringMenuDispMode(1);
        }
    }

    // Used in onPause (app in background)
    public void sendToBackground(){
        if (pControl_ != null) {
            if (Log.isDebug()) Log.d(TAG, "sendToBackground -> app with auth " + pControl_.authType + " unregister SW callback");
        }
       unregisterSteeringMenuCallback();
       unregisterStateMgrCallback();
       notifySteeringMenuDispMode(0);
    }

    public void endAudioBinding(){
        if (pControl_ != null) {
            if (Log.isDebug()) Log.d(TAG, "endAudioBinding -> app with auth " + pControl_.authType);
        }

        stopMicSession();
        unbindFromEcNcService();

        // Liberar sempre: e o que devolve o audio ao radio FM / Bluetooth nativo
        if (Log.isDebug()) Log.d(TAG, "endAudioBinding -> release audio and unregister callbacks");
        releaseAudioFocus();
        unregisterModeMgrCallback();

        unregisterSteeringMenuCallback();
        unregisterStateMgrCallback();
        unbindToWheelService();
    }

    private void notifySteeringMenuDispMode(int mode){
        if (Log.isDebug()) Log.d(TAG, "notifySteeringMenuDispMode -> boundToSteeringMenuService= " + boundToSteeringMenuService_);
        if (boundToSteeringMenuService_ && steeringMenuServiceIface_ != null) {
            try {
                if (settings_ != null && settings_.advanced != null) {
                    int idx = settings_.advanced.steeringWheelIdx();
                    if (idx > 0) {
                        if (Log.isVerbose()) Log.v(TAG, "notifySteeringMenuDispMode " + mode + " addr " + idx);
                        steeringMenuServiceIface_.notifySteeringMenuDispMode(idx, mode);
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Error registering", e);
            }
        }
    }

    private void registerSteeringMenuCallback(){
        if (Log.isDebug()) Log.d(TAG, "registerSteeringMenuCallback -> boundToSteeringMenuService= " + boundToSteeringMenuService_ + ", steeringMenuServiceCallback= " + (steeringMenuServiceCallback_ == null ? "null" : "not null"));
        try {
            if (boundToSteeringMenuService_ && steeringMenuServiceCallback_ == null) {
                int idx = settings_.advanced.steeringWheelIdx();
                if (Log.isVerbose()) Log.v(TAG, "registerCallbackEx swaddr " + idx);
                steeringMenuServiceCallback_ = new SteeringMenuServiceCallback();
                steeringMenuServiceIface_.registerCallbackEx(steeringMenuServiceCallback_, idx);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Error registerCallbackEx", e);
        }
    }

    private void unregisterSteeringMenuCallback(){
        if (Log.isDebug()) Log.d(TAG, "unregisterSteeringMenuCallback -> boundToSteeringMenuService= " + boundToSteeringMenuService_ + ", steeringMenuServiceCallback= " + (steeringMenuServiceCallback_ == null ? "null" : "not null"));
        try {
            if (boundToSteeringMenuService_ && steeringMenuServiceIface_ != null && steeringMenuServiceCallback_ != null) {
                int idx = settings_.advanced.steeringWheelIdx();
                if (Log.isVerbose()) Log.v(TAG, "unregisterCallbackEx swaddr " + idx);
                steeringMenuServiceIface_.unregisterCallbackEx(steeringMenuServiceCallback_, idx);
                steeringMenuServiceCallback_ = null;
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error unregisterCallbackEx", t);
        }
    }

    public void startMicSession() {
        if (Log.isDebug()) Log.d(TAG, "starting MicSession -> boundToEcNcService= " + boundToEcNcService_);
        if (!settings_.advanced.hondaMicVrEnabled()){
            if (Log.isDebug()) Log.d(TAG, "mic disabled");
            return;
        }

        if (boundToEcNcService_) {
            if (micVrStarted_){
                if (Log.isWarn()) Log.w(TAG, "mic session already started");
                return;
            }

            try {
                int ret = ecNcServiceIface_.startVR(true);
                if (Log.isDebug()) {
                    Log.d(TAG, "ecNcServiceIface_ startVR ret= " + ret);
//                    mainHandler_.post(() -> {
//                        Toast.makeText(context_, "ecNcServiceIface_ startVR ret= " + ret, Toast.LENGTH_SHORT).show();
//                    });
                }
                if (ret == 0) {
                    micVrStarted_ = true;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "startVR exception", e);
            }
        }
    }

    public void stopMicSession() {
        if (Log.isDebug()) Log.d(TAG, "stopping MicSession -> boundToEcNcService= " + boundToEcNcService_);
        if (!settings_.advanced.hondaMicVrEnabled()){
            if (Log.isDebug()) Log.d(TAG, "mic disabled");
            return;
        }

        if (boundToEcNcService_) {
            if (!micVrStarted_) {
                Log.w(TAG, "no mic session started, return");
                return;
            }

            try {
                int ret = ecNcServiceIface_.endVr();
                if (Log.isDebug()) {
                    Log.d(TAG, "ecNcServiceIface_ endVr ret= " + ret);
//                    mainHandler_.post(() -> {
//                        Toast.makeText(context_, "ecNcServiceIface_ endVr ret= " + ret, Toast.LENGTH_SHORT).show();
//                    });
                }
                if (ret == 0) {
                    micVrStarted_ = false;
                }
            } catch (RemoteException e) {
                Log.e(TAG, "endVr exception", e);
            }

        }
    }

    private void bindToEcNcService() {
        if (!settings_.advanced.hondaMicVrEnabled()){
            if (Log.isDebug()) Log.d(TAG, "mic disabled");
            return;
        }

        if (!boundToEcNcService_) {
            if (Log.isDebug()) Log.d(TAG, "Request binding to service " + IEcNcService.class.getName());
            Intent intent = new Intent(IEcNcService.class.getName());
            context_.bindService(intent, ecNcServiceConnection_, Context.BIND_AUTO_CREATE);
        }
    }

    private void unbindFromEcNcService() {
        if (!settings_.advanced.hondaMicVrEnabled()){
            if (Log.isDebug()) Log.d(TAG, "mic disabled");
            return;
        }

        if (boundToEcNcService_) {
            if (Log.isDebug()) Log.d(TAG, "Request unbinding to service " + IEcNcService.class.getName());
            try {
                if (ecNcServiceIface_ != null) {
                    ecNcServiceIface_.asBinder().unlinkToDeath(ecNcServiceDeathRecipient_, 0);
                }
            } catch (Throwable t) {}
            context_.unbindService(ecNcServiceConnection_);

            boundToEcNcService_ = false;
            ecNcServiceIface_ = null;
            micVrStarted_ = false; // force to false
        }
    }

    /**
     * Binds to the steering wheel service. This bind NEVER succeeded in any logged session
     * (wheelServiceBound=false in 7 of 7 on Aug 6), and the original code ignored the boolean
     * that bindService returns, so it failed without leaving a trace.
     *
     * The likely cause is intent resolution: the bind uses an implicit intent whose action is
     * the interface name, which only works if some system service declares that action in an
     * intent-filter. This version logs the resolution, and when the implicit intent matches
     * nothing it searches the installed packages for a service whose name contains
     * "SteeringMenu" and retries with an explicit component. Every step goes to the file log,
     * so the car test tells us which case we are in.
     */
    private void bindToWheelService(){
        if (boundToSteeringMenuService_) {
            return;
        }
        String action = ISteeringMenuService.class.getName();
        Intent intent = new Intent(action);

        // Diagnostic: what does the implicit intent resolve to?
        try {
            android.content.pm.PackageManager pm = context_.getPackageManager();
            java.util.List<android.content.pm.ResolveInfo> matches =
                    pm.queryIntentServices(intent, 0);
            CarlinkitFileLog.log(TAG, "wheel service: action resolves to "
                    + (matches == null ? 0 : matches.size()) + " service(s)");
            if (matches != null && !matches.isEmpty()) {
                android.content.pm.ServiceInfo si = matches.get(0).serviceInfo;
                CarlinkitFileLog.log(TAG, "wheel service: match " + si.packageName
                        + "/" + si.name + " perm=" + si.permission);
                // Explicit component avoids ambiguity when more than one matches
                intent.setClassName(si.packageName, si.name);
            } else {
                ComponentName found = findSteeringServiceComponent(pm);
                if (found != null) {
                    CarlinkitFileLog.log(TAG, "wheel service: found by scan "
                            + found.flattenToShortString() + ", binding explicitly");
                    intent = new Intent(action);
                    intent.setComponent(found);
                }
            }
        } catch (Throwable t) {
            CarlinkitFileLog.log(TAG, "wheel service: resolution query failed", t);
        }

        try {
            boolean requested = context_.bindService(
                    intent, steeringMenuServiceConnection_, Context.BIND_AUTO_CREATE);
            CarlinkitFileLog.log(TAG, "wheel service: bindService("
                    + (intent.getComponent() == null
                        ? "implicit" : intent.getComponent().flattenToShortString())
                    + ") returned " + requested);
        } catch (Throwable t) {
            // A SecurityException here means the service exists but requires a permission
            // we do not hold; that is a different fix (whitelist/permission), so log it apart
            CarlinkitFileLog.log(TAG, "wheel service: bindService threw", t);
        }
    }

    /**
     * Scans installed packages for a service whose class name contains "SteeringMenu".
     * One-shot diagnostic for the head unit, where we cannot run adb: it discovers the real
     * component name of the wheel service, whatever package Fujitsu Ten put it in.
     */
    private ComponentName findSteeringServiceComponent(android.content.pm.PackageManager pm) {
        try {
            java.util.List<android.content.pm.PackageInfo> pkgs = pm.getInstalledPackages(
                    android.content.pm.PackageManager.GET_SERVICES);
            for (android.content.pm.PackageInfo p : pkgs) {
                if (p.services == null) continue;
                for (android.content.pm.ServiceInfo s : p.services) {
                    if (s.name != null && s.name.toLowerCase().contains("steeringmenu")) {
                        CarlinkitFileLog.log(TAG, "wheel service: candidate "
                                + s.packageName + "/" + s.name
                                + " exported=" + s.exported + " perm=" + s.permission);
                        return new ComponentName(s.packageName, s.name);
                    }
                }
            }
            CarlinkitFileLog.log(TAG, "wheel service: no candidate in "
                    + pkgs.size() + " packages");
        } catch (Throwable t) {
            CarlinkitFileLog.log(TAG, "wheel service: package scan failed", t);
        }
        return null;
    }

    private void unbindToWheelService(){
        if (boundToSteeringMenuService_) {
            if (Log.isDebug()) Log.d(TAG, "Request unbinding to service " + ISteeringMenuService.class.getName());
            try {
                if (steeringMenuServiceIface_ != null) {
                    steeringMenuServiceIface_.asBinder().unlinkToDeath(steeringMenuServiceDeathRecipient_, 0);
                }
            } catch (Throwable t) {}
            context_.unbindService(steeringMenuServiceConnection_);

            boundToSteeringMenuService_ = false;
            steeringMenuServiceIface_ = null;
        }
    }

    private void unregisterModeMgrCallback() {
        if (Log.isVerbose()) Log.v(TAG, "unregisterModeMgrCallback");
        if (modeMgrManager_ == null) return;

        int idx = settings_.advanced.modeMgrAudioIdx();
        if (Log.isVerbose()) Log.v(TAG, "unregisterModeMgrCallback idx " + idx);
        try {
            int ret = modeMgrManager_.unregisterModeMgrCallback(idx);
            if (Log.isVerbose()) Log.v(TAG, "unregisterModeMgrCallback ret " + ret);
        } catch (Throwable t) {
            Log.e(TAG, "Error in unregisterModeMgrCallback", t);
        }

        if (modeMgrServiceSWKeyEventCallBack_ != null) {
            try {
                int ret = modeMgrManager_.unregisterModeMgrSWKeyEventCallback(idx);
                if (Log.isVerbose()) Log.v(TAG, "unregisterModeMgrSWKeyEventCallback ret " + ret);
            } catch (Throwable t) {
                Log.e(TAG, "Error in unregisterModeMgrSWKeyEventCallback", t);
            }
            modeMgrServiceSWKeyEventCallBack_ = null;
        }

        modeMgrServiceCallBack_ = null;
    }

    private void registerModeMgrCallback(){
        if (Log.isDebug()) Log.d(TAG, "registerModeMgrCallback");
        if (modeMgrManager_ != null) {
            int idx = settings_.advanced.modeMgrAudioIdx();
            if (Log.isVerbose()) Log.v(TAG, "registerModeMgrCallback idx " + idx);
            modeMgrServiceCallBack_ = new ModeMgrServiceCallBack();
            try {
                int ret = modeMgrManager_.registerModeMgrCallback(idx, modeMgrServiceCallBack_);
                if (Log.isVerbose()) Log.v(TAG, "registerModeMgrCallback ret " + ret);
            } catch (Throwable t) {
                Log.e(TAG, "Error in registerModeMgrCallback", t);
            }
            registerModeMgrSWKeyCallback(idx);
        } else {
            Log.w(TAG, "modeMgrManager_ null -> do nothing");
        }
    }

    /**
     * Registers the ModeMgr steering wheel key callback. This is the second, independent
     * route for steering buttons, and unlike ISteeringMenuService it does not depend on a
     * bindService that may fail: the ModeMgrManager comes from getSystemService, the same
     * channel through which the audio focus already works on this head unit.
     *
     * The field for this callback existed in the project since the OpenDroidAuto fork but
     * was never registered anywhere, so the route was silently dead. With the wheel service
     * never binding on this head unit (wheelServiceBound=false in 7 of 7 logged sessions),
     * this may be the only route that actually delivers PICKUP/TALK.
     */
    private void registerModeMgrSWKeyCallback(int idx) {
        if (modeMgrServiceSWKeyEventCallBack_ != null) {
            return;   // already registered
        }
        try {
            modeMgrServiceSWKeyEventCallBack_ = new ModeMgrSWKeyEventCallBack();
            int ret = modeMgrManager_.registerModeMgrSWKeyEventCallback(
                    idx, modeMgrServiceSWKeyEventCallBack_);
            CarlinkitFileLog.log(TAG, "registerModeMgrSWKeyEventCallback(idx=" + idx
                    + ") ret=" + ret);
            if (ret != 0) {
                // Non-zero smells like refusal; leave it unregistered so the next
                // initAudioBinding retries.
                modeMgrServiceSWKeyEventCallBack_ = null;
            }
        } catch (Throwable t) {
            modeMgrServiceSWKeyEventCallBack_ = null;
            CarlinkitFileLog.log(TAG, "registerModeMgrSWKeyEventCallback failed", t);
        }
    }

    private void registerStateMgrCallback() {
        if (Log.isDebug()) Log.d(TAG, "registerStateMgrCallback");
        if (stateMgrManager_ != null && stateMgrServiceCallBack_ == null) {
            stateMgrServiceCallBack_ = new StateMgrServiceCallBack();
            StateMgrChangeInfo info = new StateMgrChangeInfo();
            info.dayNightStateC = true;
            try {
                int ret = stateMgrManager_.registCallBack(stateMgrServiceCallBack_, info);
                if (Log.isVerbose()) Log.v(TAG, "registerStateMgrCallback ret " + ret);

                // Initial state check
                StateMgrInfo currentState = stateMgrManager_.getAllState();
                if (currentState != null) {
                    boolean isNight = currentState.dayNightState == StateMgrServiceConst.STATE_NIGHT;
                    isNight_ = isNight;
                    List<HondaListener> listenersCopy;
                    synchronized (listeners_) {
                        listenersCopy = new ArrayList<>(listeners_);
                    }
                    for (HondaListener l : listenersCopy) l.onDayNightUpdate(isNight);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error registering stateMgr callback", t);
            }
        }
    }

    private void unregisterStateMgrCallback() {
        if (Log.isDebug()) Log.d(TAG, "unregisterStateMgrCallback");
        if (stateMgrManager_ != null && stateMgrServiceCallBack_ != null) {
            try {
                stateMgrManager_.unRegistCallBack(stateMgrServiceCallBack_);
            } catch (Throwable t) {
                Log.e(TAG, "Error in unregisterStateMgrCallback", t);
            }
            stateMgrServiceCallBack_ = null;
        }
    }

    public void checkAndReconnectServices() {
        if (Log.isDebug()) Log.d(TAG, "checkAndReconnectServices -> checking if service binders are alive");

        // 1. Check Steering Menu Service
        if (boundToSteeringMenuService_) {
            boolean alive = false;
            try {
                if (steeringMenuServiceIface_ != null && steeringMenuServiceIface_.asBinder().isBinderAlive()) {
                    alive = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error checking steeringMenuService stability", t);
            }
            if (!alive) {
                Log.w(TAG, "Steering Menu Service bound but binder is dead! Re-binding...");
                unbindToWheelService();
                bindToWheelService();
            }
        } else {
            bindToWheelService();
        }

        // 2. Check EcNc Service
        if (boundToEcNcService_) {
            boolean alive = false;
            try {
                if (ecNcServiceIface_ != null && ecNcServiceIface_.asBinder().isBinderAlive()) {
                    alive = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error checking ecNcService stability", t);
            }
            if (!alive) {
                Log.w(TAG, "EcNc Service bound but binder is dead! Re-binding...");
                unbindFromEcNcService();
                bindToEcNcService();
            }
        } else {
            bindToEcNcService();
        }
    }

    /** Diagnostico: permite registrar em arquivo se o servico do volante esta ligado. */
    public boolean isWheelServiceBound() {
        return boundToSteeringMenuService_;
    }

    public boolean hasAudioFocus() {
        return hasAudioFocus_;
    }

    public Boolean isNight() {
        if (stateMgrManager_ != null) {
            try {
                StateMgrInfo currentState = stateMgrManager_.getAllState();
                if (currentState != null) {
                    boolean isNight = currentState.dayNightState == StateMgrServiceConst.STATE_NIGHT;
                    isNight_ = isNight;
                    return isNight;
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error getting all state from stateMgrManager", t);
            }
        }
        return isNight_;
    }

//    private boolean waitForCond(int timeout){
//        boolean res = false;
//        try {
//            waitCond_ = new CountDownLatch(1);
//            res = waitCond_.await(timeout, TimeUnit.MILLISECONDS);
//            if (!res) {
//                if (Log.isWarn()) Log.w(TAG, "timeout in waiting condition");
//            }
//        } catch (InterruptedException e) {
//            Log.e(TAG, "error in wait condition", e);
//        }
//
//        if (Log.isVerbose()) Log.v(TAG, "received conf/notify condition");
//        waitCond_ = null;
//
//        return res;
//    }

    private class SteeringMenuServiceCallback extends ISteeringMenuServiceCallback.Stub {
        private static final String TAG = "HondaConnectManager-ISteeringMenuServiceCallback";

        @Override
        public void onShowView() throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "onShowView");
            notifySteeringMenuDispMode(1);
        }

        @Override
        public boolean onFinishView(boolean flg, boolean anime) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "onFinishView");
            // Logged because it may be the warning that precedes the process being killed.
            // If it shows up immediately before a session ends without onBackPressed, this is
            // the hook for exiting gracefully instead of being killed.
            CarlinkitFileLog.log(TAG, "onFinishView(flg=" + flg + ", anime=" + anime + ")");
            return true;
        }

        @Override
        public boolean onSteeringSWDown(int keytype) throws RemoteException {
            if (Log.isDebug()) Log.d(TAG, "onSteeringSWDown " + keytype);
            
            List<HondaListener> listenersCopy;
            synchronized (listeners_) {
                listenersCopy = new ArrayList<>(listeners_);
            }
            for (HondaListener l : listenersCopy) {
                try {
                    l.onSteeringWheelKey(keytype);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in listener callback", t);
                }
            }
            return true;
        }
    };

    private class StateMgrServiceCallBack extends IStateMgrServiceCallBack.Stub {
        private static final String TAG = "HondaConnectManager-StateMgrServiceCallBack";

        @Override
        public void onChangeState(StateMgrInfo stateMgrInfo) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "onChangeState");
            if (stateMgrInfo.updateState.dayNightStateC) {
                boolean isNight = stateMgrInfo.dayNightState == StateMgrServiceConst.STATE_NIGHT;
                if (Log.isDebug()) Log.d(TAG, "DayNight state changed, isNight: " + isNight);
                isNight_ = isNight;
                List<HondaListener> listenersCopy;
                synchronized (listeners_) {
                    listenersCopy = new ArrayList<>(listeners_);
                }
                for (HondaListener l : listenersCopy) {
                    l.onDayNightUpdate(isNight);
                }
            }
            reportVehicleStateChange(stateMgrInfo);
        }

        /**
         * Records the head unit state changes that precede the app being killed.
         *
         * Why this exists: on 03/Aug/2026 three sessions ended mid-line with no
         * onBackPressed, no onDestroy and no exception — the signature of the process
         * being killed by the head unit, not of an application fault. Two of them had
         * been running for over 80 minutes, which is when reverse gear gets used, at the
         * end of a trip. But the log has no record of the reverse event itself, because
         * the app is killed before it can write anything.
         *
         * The head unit does report the relevant transitions: parkingSensor (the parking
         * sensor arms when reverse is engaged) and videoAddress (the video source changes
         * when the reverse camera takes the screen). The app was only ever listening to
         * dayNightStateC.
         *
         * This only LOGS, on purpose. Reacting to the signal — releasing the surface and
         * the audio to exit gracefully — is only worth writing once the log confirms which
         * field fires and in what order relative to the kill.
         */
        private void reportVehicleStateChange(StateMgrInfo info) {
            try {
                StringBuilder sb = null;
                if (info.updateState.parkingSensorC) {
                    sb = new StringBuilder("head unit state: parkingSensor=")
                            .append(info.parkingSensor);
                }
                if (info.updateState.videoAddressC) {
                    if (sb == null) sb = new StringBuilder("head unit state:");
                    else sb.append(" |");
                    sb.append(" videoAddress=").append(info.videoAddress)
                      .append(" (last=").append(info.lastVideoAddress).append(")");
                }
                if (info.updateState.screenOffC) {
                    if (sb == null) sb = new StringBuilder("head unit state:");
                    else sb.append(" |");
                    sb.append(" screenOff=").append(info.screenOff);
                }
                if (info.updateState.sourceFlowC) {
                    if (sb == null) sb = new StringBuilder("head unit state:");
                    else sb.append(" |");
                    sb.append(" sourceFlow=").append(info.sourceFlow);
                }
                if (sb != null) {
                    CarlinkitFileLog.log(TAG, sb.toString());
                }
            } catch (Throwable t) {
                // Nao deixar a instrumentacao derrubar o callback de estado.
                Log.e(TAG, "failed to report vehicle state", t);
            }
        }
    }

    /**
     * Steering wheel keys delivered through the ModeMgr channel.
     *
     * The framework constants describe two dimensions: the physical key
     * ({@code KEYCODE_STRG_*}, base -65536) and a semantic extra ({@code EXTRA_STRG_KEY_*}:
     * OFFHOOK=1/2/7 for answering, ONHOOK=3/4/5 for hanging up, TALK=6/9 and SIRI_START=8
     * for the assistant). Which of the two carries the useful value on this head unit is
     * unknown until it runs in the car, so both are mapped and every event is logged raw.
     *
     * The mapped result is delivered through the same {@code HondaListener.onSteeringWheelKey}
     * used by the ISteeringMenuService callback, translated to the {@code HondaKey} codes the
     * rest of the app already understands. Deduplication against the KeyEvent route happens
     * downstream, in the Activity.
     */
    private class ModeMgrSWKeyEventCallBack extends IModeMgrServiceSWKeyEventCallBack.Stub {

        private static final String TAG = "HondaConnectManager-SWKeyEvent";

        @Override
        public void rcvStrgKeyEvent(int keyCode, int extra) throws RemoteException {
            // Raw log first: this line is the discovery instrument for tomorrow's test
            CarlinkitFileLog.log(TAG, "rcvStrgKeyEvent keyCode=" + keyCode + " extra=" + extra);

            int hondaKey = mapToHondaKey(keyCode, extra);
            if (hondaKey == -1) {
                return;   // volume and unmapped keys stay with the head unit
            }
            List<HondaListener> listenersCopy;
            synchronized (listeners_) {
                listenersCopy = new ArrayList<>(listeners_);
            }
            for (HondaListener l : listenersCopy) {
                try {
                    l.onSteeringWheelKey(hondaKey);
                } catch (Throwable t) {
                    Log.e(TAG, "Error in listener callback", t);
                }
            }
        }

        /** Translates ModeMgr keyCode/extra to the HondaKey codes of SteeringWheelMapper. */
        private int mapToHondaKey(int keyCode, int extra) {
            // The extra is more specific; trust it first
            switch (extra) {
                case 1: case 2: case 7:   // OFFHOOK, OFFHOOK_L, OFFHOOK_REDIAL
                    return 8;             // HondaKey.PICK_UP
                case 3: case 4: case 5:   // ONHOOK, ONHOOK_1SEC_L, ONHOOK_5SEC_L
                    return 9;             // HondaKey.HANG_UP
                case 6: case 8: case 9:   // TALK, SIRI_START, TALK_L
                    return 10;            // HondaKey.TALK
                default:
                    break;
            }
            switch (keyCode) {
                case -65528:              // KEYCODE_STRG_PICKUP
                    return 8;
                case -65526: case -65522: // KEYCODE_STRG_TALK, TALK_L
                    return 10;
                case -65533:              // KEYCODE_STRG_CH_UP
                    return 3;             // HondaKey.TRACK_UP
                case -65532:              // KEYCODE_STRG_CH_DOWN
                    return 4;             // HondaKey.TRACK_DOWN
                default:
                    return -1;
            }
        }
    }

    private class ModeMgrServiceCallBack extends IModeMgrServiceCallBack.Stub {

        private static final String TAG = "HondaConnectManager-ModeMgrServiceCallBack";

        public void rcvOnInsCmd(int modestate) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvOnInsCmd modestate = " + modestate);
            int idx = settings_.advanced.modeMgrAudioIdx();

            if (Log.isVerbose()) Log.v(TAG, "sendModeMgrOnCnf idx= " + idx + ",state = " + HondaConnectManager.ModeMgrMode.CONFIRM_MODE);
            int ret = modeMgrManager_.sendModeMgrOnCnf(idx, HondaConnectManager.ModeMgrMode.CONFIRM_MODE);
            if (Log.isVerbose()) Log.v(TAG, "sendModeMgrOnCnf ret = " + ret);

            if (Log.isVerbose()) Log.v(TAG, "notifyModeMgrStatus idx= " + idx + ", state = " + HondaConnectManager.ModeMgrMode.NOTIFY_MODE);
            ret = modeMgrManager_.notifyModeMgrStatus(idx, HondaConnectManager.ModeMgrMode.NOTIFY_MODE);
            if (Log.isVerbose()) Log.v(TAG, "notifyModeMgrStatus ret = " + ret);

            if (Log.isVerbose()) Log.v(TAG, "notifyModeMgrStatus iAudioAddr = " + modeMgrManager_.getModeMgrOnAudioAddr());
            if (Log.isVerbose()) Log.v(TAG, "notifyModeMgrStatus iVideoAddr = " + modeMgrManager_.getModeMgrOnVideoAddr());

//            if (waitCond_ != null) {
//                waitCond_.countDown();
//            }

        }

        public void rcvOffInsCmd(int modestate) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvOffInsCmd modestate = " + modestate);

            int sound_param = modestate & 1;
            int image_param = modestate & 2;
            if (Log.isVerbose()) Log.v(TAG, "rcvOffInsCmd sound_param= " + sound_param + " image_param= " + image_param);

            int idx = settings_.advanced.modeMgrAudioIdx();
            if (Log.isVerbose()) Log.v(TAG, "rcvOffInsCmd sendModeMgrOffCnf idx= " + idx + ", state = " + HondaConnectManager.ModeMgrMode.CONFIRM_MODE);
            int ret = modeMgrManager_.sendModeMgrOffCnf(idx, HondaConnectManager.ModeMgrMode.CONFIRM_MODE);
            if (Log.isVerbose()) Log.v(TAG, "rcvOffInsCmd sendModeMgrOffCnf ret = " + ret);

//            if (waitCond_ != null) {
//                waitCond_.countDown();
//            }

//            if (sound_param == 1 && image_param == 0) {
//                int ret = modeMgrManager_.sendModeMgrOffReq(settings_.advanced.modeMgrAudioIdx(), ModeMgrMode.REQUEST_MODE.mode);
//                if (Log.isVerbose()) Log.v(TAG, "sendModeMgrOffReq ret = " + ret);
//            } else {
//                int ret = modeMgrManager_.sendModeMgrOffCnf(settings_.advanced.modeMgrAudioIdx(), ModeMgrMode.CONFIRM_MODE.mode);
//                if (Log.isVerbose()) Log.v(TAG, "sendModeMgrOffCnf ret = " + ret);
////                ret = modeMgrManager_.unregisterModeMgrCallback(136);
////                if (Log.isVerbose()) Log.v(TAG, "unregisterModeMgrCallback ret = " + ret);
//            }

        }

        public void rcvOnReqCmdFailed(int audioaddr, int videoaddr, int reason) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvOnReqCmdFailed audioaddr = " + audioaddr + " , videoaddr = " + videoaddr + " , reason = " + reason);
        }

        public void rcvVideoPwrCmd(int addr) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvVideoPwrCmd  addr = " + addr);
        }

        public void rcvAudioPwrONCmd(int addr) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvAudioPwrONCmd  addr = " + addr);
        }

        public void rcvAudioPwrOFFCmd() throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "rcvAudioPwrOFFCmd -S");
        }

        public void insDispApl(int disp, int extInfo1, int extInfo2) throws RemoteException {
            if (Log.isVerbose()) Log.v(TAG, "insDispApl " + disp + "/" + extInfo1 + "/" + extInfo2);
        }
    };
}

