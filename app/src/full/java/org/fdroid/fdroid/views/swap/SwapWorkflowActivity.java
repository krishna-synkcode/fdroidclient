package org.fdroid.fdroid.views.swap;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.Settings;
import android.support.annotation.LayoutRes;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.StringRes;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v4.view.MenuItemCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.SearchView;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import cc.mvdan.accesspoint.WifiApControl;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;
import org.fdroid.fdroid.BuildConfig;
import org.fdroid.fdroid.FDroidApp;
import org.fdroid.fdroid.NfcHelper;
import org.fdroid.fdroid.Preferences;
import org.fdroid.fdroid.R;
import org.fdroid.fdroid.Utils;
import org.fdroid.fdroid.data.Apk;
import org.fdroid.fdroid.data.App;
import org.fdroid.fdroid.data.NewRepoConfig;
import org.fdroid.fdroid.installer.InstallManagerService;
import org.fdroid.fdroid.installer.Installer;
import org.fdroid.fdroid.localrepo.LocalRepoManager;
import org.fdroid.fdroid.localrepo.SwapService;
import org.fdroid.fdroid.localrepo.SwapView;
import org.fdroid.fdroid.localrepo.peers.Peer;
import org.fdroid.fdroid.net.BluetoothDownloader;
import org.fdroid.fdroid.net.HttpDownloader;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

/**
 * This activity will do its best to show the most relevant screen about swapping to the user.
 * The problem comes when there are two competing goals - 1) Show the user a list of apps from another
 * device to download and install, and 2) Prepare your own list of apps to share.
 */
@SuppressWarnings("LineLength")
public class SwapWorkflowActivity extends AppCompatActivity {
    private static final String TAG = "SwapWorkflowActivity";

    /**
     * When connecting to a swap, we then go and initiate a connection with that
     * device and ask if it would like to swap with us. Upon receiving that request
     * and agreeing, we don't then want to be asked whether we want to swap back.
     * This flag protects against two devices continually going back and forth
     * among each other offering swaps.
     */
    public static final String EXTRA_PREVENT_FURTHER_SWAP_REQUESTS = "preventFurtherSwap";
    public static final String EXTRA_CONFIRM = "EXTRA_CONFIRM";

    /**
     * Ensure that we don't try to handle specific intents more than once in onResume()
     * (e.g. the "Do you want to swap back with ..." intent).
     */
    public static final String EXTRA_SWAP_INTENT_HANDLED = "swapIntentHandled";

    private ViewGroup container;

    private static final int CONNECT_TO_SWAP = 1;
    private static final int REQUEST_BLUETOOTH_ENABLE_FOR_SWAP = 2;
    private static final int REQUEST_BLUETOOTH_DISCOVERABLE = 3;
    private static final int REQUEST_BLUETOOTH_ENABLE_FOR_SEND = 4;
    private static final int REQUEST_WRITE_SETTINGS_PERMISSION = 5;

    private Toolbar toolbar;
    private SwapView currentView;
    private boolean hasPreparedLocalRepo;
    private PrepareSwapRepo updateSwappableAppsTask;
    private NewRepoConfig confirmSwapConfig;
    private LocalBroadcastManager localBroadcastManager;
    private WifiManager wifiManager;

    public static void requestSwap(Context context, String repo) {
        Uri repoUri = Uri.parse(repo);
        Intent intent = new Intent(context, SwapWorkflowActivity.class);
        intent.setData(repoUri);
        intent.putExtra(EXTRA_CONFIRM, true);
        intent.putExtra(EXTRA_PREVENT_FURTHER_SWAP_REQUESTS, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @NonNull
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder binder) {
            Utils.debugLog(TAG, "Swap service connected. Will hold onto it so we can talk to it regularly.");
            service = ((SwapService.Binder) binder).getService();
            showRelevantView();
        }

        // TODO: What causes this? Do we need to stop swapping explicitly when this is invoked?
        @Override
        public void onServiceDisconnected(ComponentName className) {
            Utils.debugLog(TAG, "Swap service disconnected");
            service = null;
            // TODO: What to do about the UI in this instance?
        }
    };

    @Nullable
    private SwapService service;

    @NonNull
    public SwapService getService() {
        if (service == null) {
            // *Slightly* more informative than a null-pointer error that would otherwise happen.
            throw new IllegalStateException("Trying to access swap service before it was initialized.");
        }
        return service;
    }

    @Override
    public void onBackPressed() {
        if (currentView.getLayoutResId() == SwapService.STEP_INTRO) {
            SwapService.stop(this);  // TODO SwapService should always be running, while swap is running
            finish();
        } else {
            // TODO: Currently StartSwapView is handleed by the SwapWorkflowActivity as a special case, where
            // if getLayoutResId is STEP_INTRO, don't even bother asking for getPreviousStep. But that is a
            // bit messy. It would be nicer if this was handled using the same mechanism as everything
            // else.
            int nextStep = -1;
            switch (currentView.getLayoutResId()) {
                case R.layout.swap_confirm_receive:
                    nextStep = SwapService.STEP_INTRO;
                    break;
                case R.layout.swap_connecting:
                    nextStep = R.layout.swap_select_apps;
                    break;
                case R.layout.swap_initial_loading:
                    nextStep = R.layout.swap_join_wifi;
                    break;
                case R.layout.swap_join_wifi:
                    nextStep = SwapService.STEP_INTRO;
                    break;
                case R.layout.swap_nfc:
                    nextStep = R.layout.swap_join_wifi;
                    break;
                case R.layout.swap_select_apps:
                    // TODO: The STEP_JOIN_WIFI step isn't shown first, need to make it
                    // so that it is, or so that this doesn't go back there.
                    nextStep = getState().isConnectingWithPeer() ? SwapService.STEP_INTRO : R.layout.swap_join_wifi;
                    break;
                case R.layout.swap_send_fdroid:
                    nextStep = SwapService.STEP_INTRO;
                    break;
                case R.layout.swap_start_swap:
                    nextStep = SwapService.STEP_INTRO;
                    break;
                case R.layout.swap_success:
                    nextStep = SwapService.STEP_INTRO;
                    break;
                case R.layout.swap_wifi_qr:
                    nextStep = R.layout.swap_join_wifi;
                    break;
            }
            getService().setCurrentView(nextStep);
            showRelevantView();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ((FDroidApp) getApplication()).setSecureWindow(this);
        super.onCreate(savedInstanceState);

        // The server should not be doing anything or occupying any (noticeable) resources
        // until we actually ask it to enable swapping. Therefore, we will start it nice and
        // early so we don't have to wait until it is connected later.
        Intent service = new Intent(this, SwapService.class);
        if (bindService(service, serviceConnection, Context.BIND_AUTO_CREATE)) {
            startService(service);
        }

        setContentView(R.layout.swap_activity);

        toolbar = (Toolbar) findViewById(R.id.toolbar);
        toolbar.setTitleTextAppearance(getApplicationContext(), R.style.SwapTheme_Wizard_Text_Toolbar);
        setSupportActionBar(toolbar);

        container = (ViewGroup) findViewById(R.id.container);

        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);

        new SwapDebug().logStatus();
    }

    @Override
    protected void onDestroy() {
        unbindService(serviceConnection);
        super.onDestroy();
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();

        MenuInflater menuInflater = getMenuInflater();
        switch (currentView.getLayoutResId()) {
            case R.layout.swap_select_apps:
                menuInflater.inflate(R.menu.swap_next_search, menu);
                setUpNextButton(menu, R.string.next);
                setUpSearchView(menu);
                return true;
            case R.layout.swap_success:
                menuInflater.inflate(R.menu.swap_search, menu);
                setUpSearchView(menu);
                return true;
            case R.layout.swap_join_wifi:
                menuInflater.inflate(R.menu.swap_next, menu);
                setUpNextButton(menu, R.string.next);
                return true;
            case R.layout.swap_nfc:
                menuInflater.inflate(R.menu.swap_next, menu);
                setUpNextButton(menu, R.string.skip);
                return true;
        }

        return super.onPrepareOptionsMenu(menu);
    }

    private void setUpNextButton(Menu menu, @StringRes int titleResId) {
        MenuItem next = menu.findItem(R.id.action_next);
        CharSequence title = getString(titleResId);
        next.setTitle(title);
        next.setTitleCondensed(title);
        MenuItemCompat.setShowAsAction(next,
                MenuItemCompat.SHOW_AS_ACTION_ALWAYS | MenuItemCompat.SHOW_AS_ACTION_WITH_TEXT);
        next.setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                sendNext();
                return true;
            }
        });
    }

    void sendNext() {
        int currentLayoutResId = currentView.getLayoutResId();
        switch (currentLayoutResId) {
            case R.layout.swap_select_apps:
                onAppsSelected();
                break;
            case R.layout.swap_join_wifi:
                inflateSwapView(R.layout.swap_select_apps);
                break;
            case R.layout.swap_nfc:
                inflateSwapView(R.layout.swap_wifi_qr);
                break;
        }
    }

    private void setUpSearchView(Menu menu) {
        SearchView searchView = new SearchView(this);

        MenuItem searchMenuItem = menu.findItem(R.id.action_search);
        MenuItemCompat.setActionView(searchMenuItem, searchView);
        MenuItemCompat.setShowAsAction(searchMenuItem, MenuItemCompat.SHOW_AS_ACTION_IF_ROOM);

        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {

            @Override
            public boolean onQueryTextSubmit(String newText) {
                String currentFilterString = currentView.getCurrentFilterString();
                String newFilter = !TextUtils.isEmpty(newText) ? newText : null;
                if (currentFilterString == null && newFilter == null) {
                    return true;
                }
                if (currentFilterString != null && currentFilterString.equals(newFilter)) {
                    return true;
                }
                currentView.setCurrentFilterString(newFilter);
                if (currentView instanceof SelectAppsView) {
                    getSupportLoaderManager().restartLoader(currentView.getLayoutResId(), null,
                            (SelectAppsView) currentView);
                } else if (currentView instanceof SwapSuccessView) {
                    getSupportLoaderManager().restartLoader(currentView.getLayoutResId(), null,
                            (SwapSuccessView) currentView);
                } else {
                    throw new IllegalStateException(currentView.getClass() + " does not have Loader!");
                }
                return true;
            }

            @Override
            public boolean onQueryTextChange(String s) {
                return true;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        checkIncomingIntent();
        showRelevantView();
    }

    /**
     * Check whether incoming {@link Intent} is a swap repo, and ensure that
     * it is a valid swap URL.  The hostname can only be either an IP or
     * Bluetooth address.
     */
    private void checkIncomingIntent() {
        Intent intent = getIntent();
        Uri uri = intent.getData();
        if (uri != null && !HttpDownloader.isSwapUrl(uri) && !BluetoothDownloader.isBluetoothUri(uri)) {
            String msg = getString(R.string.swap_toast_invalid_url, uri);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
            return;
        }

        if (intent.getBooleanExtra(EXTRA_CONFIRM, false) && !intent.getBooleanExtra(EXTRA_SWAP_INTENT_HANDLED, false)) {
            // Storing config in this variable will ensure that when showRelevantView() is next
            // run, it will show the connect swap view (if the service is available).
            intent.putExtra(EXTRA_SWAP_INTENT_HANDLED, true);
            confirmSwapConfig = new NewRepoConfig(this, intent);
        }
    }

    public void promptToSelectWifiNetwork() {
        //
        // On Android >= 5.0, the neutral button is the one by itself off to the left of a dialog
        // (not the negative button). Thus, the layout of this dialogs buttons should be:
        //
        // |                                 |
        // +---------------------------------+
        // | Cancel           Hotspot   WiFi |
        // +---------------------------------+
        //
        // TODO: Investigate if this should be set dynamically for earlier APIs.
        //
        new AlertDialog.Builder(this)
                .setTitle(R.string.swap_join_same_wifi)
                .setMessage(R.string.swap_join_same_wifi_desc)
                .setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        // Do nothing
                    }
                })
                .setPositiveButton(R.string.wifi, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        SwapService.putWifiEnabledBeforeSwap(wifiManager.isWifiEnabled());
                        wifiManager.setWifiEnabled(true);
                        Intent intent = new Intent(WifiManager.ACTION_PICK_WIFI_NETWORK);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    }
                })
                .setNegativeButton(R.string.wifi_ap, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (Build.VERSION.SDK_INT >= 26) {
                            showTetheringSettings();
                        } else if (Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(getBaseContext())) {
                            requestWriteSettingsPermission();
                        } else {
                            setupWifiAP();
                        }
                    }
                })
                .create().show();
    }

    private void setupWifiAP() {
        WifiApControl ap = WifiApControl.getInstance(this);
        wifiManager.setWifiEnabled(false);
        if (ap.enable()) {
            Toast.makeText(this, R.string.swap_toast_hotspot_enabled, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, R.string.swap_toast_could_not_enable_hotspot, Toast.LENGTH_LONG).show();
            Log.e(TAG, "Could not enable WiFi AP.");
        }
    }

    private void showRelevantView() {
        showRelevantView(false);
    }

    private void showRelevantView(boolean forceReload) {

        if (service == null) {
            inflateSwapView(R.layout.swap_initial_loading);
            return;
        }

        // This is separate from the switch statement below, because it is usually populated
        // during onResume, when there is a high probability of not having a swap service
        // available. Thus, we were unable to set the state of the swap service appropriately.
        if (confirmSwapConfig != null) {
            showConfirmSwap(confirmSwapConfig);
            confirmSwapConfig = null;
            return;
        }

        if (!forceReload && (container.getVisibility() == View.GONE || currentView != null && currentView.getLayoutResId() == service.getCurrentView())) {
            // Already showing the correct step, so don't bother changing anything.
            return;
        }

        int currentView = service.getCurrentView();
        switch (currentView) {
            case SwapService.STEP_INTRO:
                showIntro();
                return;
            case R.layout.swap_nfc:
                if (!attemptToShowNfc()) {
                    inflateSwapView(R.layout.swap_wifi_qr);
                    return;
                }
                break;
            case R.layout.swap_connecting:
                // TODO: Properly decide what to do here (i.e. returning to the activity after it was connecting)...
                inflateSwapView(R.layout.swap_start_swap);
                return;
        }
        inflateSwapView(currentView);
    }

    public SwapService getState() {
        return service;
    }

    public SwapView inflateSwapView(@LayoutRes int viewRes) {
        container.removeAllViews();
        View view = ((LayoutInflater) getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(viewRes, container, false);
        currentView = (SwapView) view;
        currentView.setLayoutResId(viewRes);

        // Don't actually set the step to STEP_INITIAL_LOADING, as we are going to use this view
        // purely as a placeholder for _whatever view is meant to be shown_.
        if (currentView.getLayoutResId() != R.layout.swap_initial_loading) {
            if (service == null) {
                throw new IllegalStateException("We are not in the STEP_INITIAL_LOADING state, but the service is not ready.");
            }
            service.setCurrentView(currentView.getLayoutResId());
        }

        toolbar.setBackgroundColor(currentView.getToolbarColour());
        toolbar.setTitle(currentView.getToolbarTitle());
        toolbar.setNavigationIcon(R.drawable.ic_close_white_24dp);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onToolbarCancel();
            }
        });
        container.addView(view);
        supportInvalidateOptionsMenu();

        return currentView;
    }

    private void onToolbarCancel() {
        SwapService.stop(this);
        finish();
    }

    public void showIntro() {
        // If we were previously swapping with a specific client, forget that we were doing that,
        // as we are starting over now.
        getService().swapWith(null);

        if (!getService().isEnabled()) {
            if (!LocalRepoManager.get(this).getIndexJar().exists()) {
                Utils.debugLog(TAG, "Preparing initial repo with only F-Droid, until we have allowed the user to configure their own repo.");
                new PrepareInitialSwapRepo().execute();
            }
        }

        inflateSwapView(R.layout.swap_start_swap);
    }

    private void showConfirmSwap(@NonNull NewRepoConfig config) {
        ((ConfirmReceiveView) inflateSwapView(R.layout.swap_confirm_receive)).setup(config);
        TextView descriptionTextView = (TextView) findViewById(R.id.text_description);
        descriptionTextView.setText(getResources().getString(R.string.swap_confirm_connect, config.getHost()));
    }

    public void startQrWorkflow() {
        if (!getService().isEnabled()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.not_visible_nearby)
                    .setMessage(R.string.not_visible_nearby_description)
                    .setCancelable(true)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            // Do nothing. The dialog will get dismissed anyway, which is all we ever wanted...
                        }
                    })
                    .create().show();
        } else {
            inflateSwapView(R.layout.swap_wifi_qr);
        }
    }

    /**
     * On {@code android-26}, only apps with privileges can access
     * {@code WRITE_SETTINGS}.  So this just shows the tethering settings
     * for the user to do it themselves.
     */
    public void showTetheringSettings() {
        final Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        final ComponentName cn = new ComponentName("com.android.settings",
                "com.android.settings.TetherSettings");
        intent.setComponent(cn);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    @TargetApi(23)
    public void requestWriteSettingsPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivityForResult(intent, REQUEST_WRITE_SETTINGS_PERMISSION);
    }

    public void sendFDroid() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null
                || Build.VERSION.SDK_INT >= 23 // TODO make Bluetooth work with content:// URIs
                || (!adapter.isEnabled() && getService().getWifiSwap().isConnected())) {
            inflateSwapView(R.layout.swap_send_fdroid);
        } else {
            sendFDroidBluetooth();
        }
    }

    /**
     * Send the F-Droid APK via Bluetooth.  If Bluetooth has not been
     * enabled/turned on, then enabling device discoverability will
     * automatically enable Bluetooth.
     */
    public void sendFDroidBluetooth() {
        if (BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            sendFDroidApk();
        } else {
            Intent discoverBt = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverBt.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120);
            startActivityForResult(discoverBt, REQUEST_BLUETOOTH_ENABLE_FOR_SEND);
        }
    }

    private void sendFDroidApk() {
        ((FDroidApp) getApplication()).sendViaBluetooth(this, Activity.RESULT_OK, BuildConfig.APPLICATION_ID);
    }

    // TODO: Figure out whether they have changed since last time UpdateAsyncTask was run.
    // If the local repo is running, then we can ask it what apps it is swapping and compare with that.
    // Otherwise, probably will need to scan the file system.
    public void onAppsSelected() {
        if (updateSwappableAppsTask == null && !hasPreparedLocalRepo) {
            updateSwappableAppsTask = new PrepareSwapRepo(getService().getAppsToSwap());
            updateSwappableAppsTask.execute();
            getService().setCurrentView(R.layout.swap_connecting);
            inflateSwapView(R.layout.swap_connecting);
        } else {
            onLocalRepoPrepared();
        }
    }

    /**
     * Once the UpdateAsyncTask has finished preparing our repository index, we can
     * show the next screen to the user. This will be one of two things:
     * * If we directly selected a peer to swap with initially, we will skip straight to getting
     * the list of apps from that device.
     * * Alternatively, if we didn't have a person to connect to, and instead clicked "Scan QR Code",
     * then we want to show a QR code or NFC dialog.
     */
    public void onLocalRepoPrepared() {
        updateSwappableAppsTask = null;
        hasPreparedLocalRepo = true;
        if (getService().isConnectingWithPeer()) {
            startSwappingWithPeer();
        } else if (!attemptToShowNfc()) {
            inflateSwapView(R.layout.swap_wifi_qr);
        }
    }

    private void startSwappingWithPeer() {
        getService().connectToPeer();
        inflateSwapView(R.layout.swap_connecting);
    }

    private boolean attemptToShowNfc() {
        // TODO: What if NFC is disabled? Hook up with NfcNotEnabledActivity? Or maybe only if they
        // click a relevant button?

        // Even if they opted to skip the message which says "Touch devices to swap",
        // we still want to actually enable the feature, so that they could touch
        // during the wifi qr code being shown too.
        boolean nfcMessageReady = NfcHelper.setPushMessage(this, Utils.getSharingUri(FDroidApp.repo));

        if (Preferences.get().showNfcDuringSwap() && nfcMessageReady) {
            inflateSwapView(R.layout.swap_nfc);
            return true;
        }
        return false;
    }

    public void swapWith(Peer peer) {
        getService().swapWith(peer);
        inflateSwapView(R.layout.swap_select_apps);
    }

    /**
     * This is for when we initiate a swap by viewing the "Are you sure you want to swap with" view
     * This can arise either:
     * * As a result of scanning a QR code (in which case we likely already have a repo setup) or
     * * As a result of the other device selecting our device in the "start swap" screen, in which
     * case we are likely just sitting on the start swap screen also, and haven't configured
     * anything yet.
     */
    public void swapWith(NewRepoConfig repoConfig) {
        Peer peer = repoConfig.toPeer();
        if (getService().getCurrentView() == SwapService.STEP_INTRO || getService().getCurrentView() == R.layout.swap_confirm_receive) {
            // This will force the "Select apps to swap" workflow to begin.
            // TODO: Find a better way to decide whether we need to select the apps. Not sure if we
            //       can or cannot be in STEP_INTRO with a full blown repo ready to swap.
            swapWith(peer);
        } else {
            getService().swapWith(repoConfig.toPeer());
            startSwappingWithPeer();
        }
    }

    public void denySwap() {
        showIntro();
    }

    /**
     * Attempts to open a QR code scanner, in the hope a user will then scan the QR code of another
     * device configured to swapp apps with us. Delegates to the zxing library to do so.
     */
    public void initiateQrScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.initiateScan();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        IntentResult scanResult = IntentIntegrator.parseActivityResult(requestCode, resultCode, intent);
        if (scanResult != null) {
            if (scanResult.getContents() != null) {
                NewRepoConfig repoConfig = new NewRepoConfig(this, scanResult.getContents());
                if (repoConfig.isValidRepo()) {
                    confirmSwapConfig = repoConfig;
                    showRelevantView();
                } else {
                    Toast.makeText(this, R.string.swap_qr_isnt_for_swap, Toast.LENGTH_SHORT).show();
                }
            }
        } else if (requestCode == CONNECT_TO_SWAP && resultCode == Activity.RESULT_OK) {
            finish();
        } else if (requestCode == REQUEST_WRITE_SETTINGS_PERMISSION) {
            if (Build.VERSION.SDK_INT >= 23 && Settings.System.canWrite(this)) {
                setupWifiAP();
            }
        } else if (requestCode == REQUEST_BLUETOOTH_ENABLE_FOR_SWAP) {

            if (resultCode == RESULT_OK) {
                Utils.debugLog(TAG, "User enabled Bluetooth, will make sure we are discoverable.");
                ensureBluetoothDiscoverableThenStart();
            } else {
                Utils.debugLog(TAG, "User chose not to enable Bluetooth, so doing nothing");
                SwapService.putBluetoothVisibleUserPreference(false);
            }

        } else if (requestCode == REQUEST_BLUETOOTH_DISCOVERABLE) {

            if (resultCode != RESULT_CANCELED) {
                Utils.debugLog(TAG, "User made Bluetooth discoverable, will proceed to start bluetooth server.");
                getState().getBluetoothSwap().startInBackground(); // TODO replace with Intent to SwapService
            } else {
                Utils.debugLog(TAG, "User chose not to make Bluetooth discoverable, so doing nothing");
                SwapService.putBluetoothVisibleUserPreference(false);
            }

        } else if (requestCode == REQUEST_BLUETOOTH_ENABLE_FOR_SEND) {
            sendFDroidApk();
        }
    }

    /**
     * The process for setting up bluetooth is as follows:
     * <ul>
     * <li>Assume we have bluetooth available (otherwise the button which allowed us to start
     * the bluetooth process should not have been available)</li>
     * <li>Ask user to enable (if not enabled yet)</li>
     * <li>Start bluetooth server socket</li>
     * <li>Enable bluetooth discoverability, so that people can connect to our server socket.</li>
     * </ul>
     * Note that this is a little different than the usual process for bluetooth _clients_, which
     * involves pairing and connecting with other devices.
     */
    public void startBluetoothSwap() {

        Utils.debugLog(TAG, "Initiating Bluetooth swap, will ensure the Bluetooth devices is enabled and discoverable before starting server.");
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();

        if (adapter != null) {
            if (adapter.isEnabled()) {
                Utils.debugLog(TAG, "Bluetooth enabled, will check if device is discoverable with device.");
                ensureBluetoothDiscoverableThenStart();
            } else {
                Utils.debugLog(TAG, "Bluetooth disabled, asking user to enable it.");
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_BLUETOOTH_ENABLE_FOR_SWAP);
            }
        }
    }

    private void ensureBluetoothDiscoverableThenStart() {
        Utils.debugLog(TAG, "Ensuring Bluetooth is in discoverable mode.");
        if (BluetoothAdapter.getDefaultAdapter().getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {

            // TODO: Listen for BluetoothAdapter.ACTION_SCAN_MODE_CHANGED and respond if discovery
            // is cancelled prematurely.

            // 3600 is new maximum! TODO: What about when this expires? What if user manually disables discovery?
            final int discoverableTimeout = 3600;

            Utils.debugLog(TAG, "Not currently in discoverable mode, so prompting user to enable.");
            Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            intent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, discoverableTimeout);
            startActivityForResult(intent, REQUEST_BLUETOOTH_DISCOVERABLE);
        }

        if (service == null) {
            throw new IllegalStateException("Can't start Bluetooth swap because service is null for some strange reason.");
        }

        service.getBluetoothSwap().startInBackground();  // TODO replace with Intent to SwapService
    }

    class PrepareInitialSwapRepo extends PrepareSwapRepo {
        PrepareInitialSwapRepo() {
            super(new HashSet<>(Arrays.asList(new String[]{BuildConfig.APPLICATION_ID})));
        }
    }

    class PrepareSwapRepo extends AsyncTask<Void, Void, Void> {

        public static final String ACTION = "PrepareSwapRepo.Action";
        public static final String EXTRA_MESSAGE = "PrepareSwapRepo.Status.Message";
        public static final String EXTRA_TYPE = "PrepareSwapRepo.Action.Type";
        public static final int TYPE_STATUS = 0;
        public static final int TYPE_COMPLETE = 1;
        public static final int TYPE_ERROR = 2;

        @NonNull
        protected final Set<String> selectedApps;

        @NonNull
        protected final Uri sharingUri;

        @NonNull
        protected final Context context;

        PrepareSwapRepo(@NonNull Set<String> apps) {
            context = SwapWorkflowActivity.this;
            selectedApps = apps;
            sharingUri = Utils.getSharingUri(FDroidApp.repo);
        }

        private void broadcast(int type) {
            broadcast(type, null);
        }

        private void broadcast(int type, String message) {
            Intent intent = new Intent(ACTION);
            intent.putExtra(EXTRA_TYPE, type);
            if (message != null) {
                Utils.debugLog(TAG, "Preparing swap: " + message);
                intent.putExtra(EXTRA_MESSAGE, message);
            }
            LocalBroadcastManager.getInstance(SwapWorkflowActivity.this).sendBroadcast(intent);
        }

        @Override
        protected Void doInBackground(Void... params) {
            try {
                final LocalRepoManager lrm = LocalRepoManager.get(context);
                broadcast(TYPE_STATUS, getString(R.string.deleting_repo));
                lrm.deleteRepo();
                for (String app : selectedApps) {
                    broadcast(TYPE_STATUS, String.format(getString(R.string.adding_apks_format), app));
                    lrm.addApp(context, app);
                }
                lrm.writeIndexPage(sharingUri.toString());
                broadcast(TYPE_STATUS, getString(R.string.writing_index_jar));
                lrm.writeIndexJar();
                broadcast(TYPE_STATUS, getString(R.string.linking_apks));
                lrm.copyApksToRepo();
                broadcast(TYPE_STATUS, getString(R.string.copying_icons));
                // run the icon copy without progress, its not a blocker
                new Thread() {
                    @Override
                    public void run() {
                        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                        lrm.copyIconsToRepo();
                    }
                }.start();

                broadcast(TYPE_COMPLETE);
            } catch (Exception e) {
                broadcast(TYPE_ERROR);
                Log.e(TAG, "", e);
            }
            return null;
        }
    }

    /**
     * Helper class to try and make sense of what the swap workflow is currently doing.
     * The more technologies are involved in the process (e.g. Bluetooth/Wifi/NFC/etc)
     * the harder it becomes to reason about and debug the whole thing. Thus,this class
     * will periodically dump the state to logcat so that it is easier to see when certain
     * protocols are enabled/disabled.
     * <p>
     * To view only this output from logcat:
     * <p>
     * adb logcat | grep 'Swap Status'
     * <p>
     * To exclude this output from logcat (it is very noisy):
     * <p>
     * adb logcat | grep -v 'Swap Status'
     */
    class SwapDebug {

        public void logStatus() {

            if (true) return; // NOPMD

            String message = "";
            if (service == null) {
                message = "No swap service";
            } else {
                String bluetooth = service.getBluetoothSwap().isConnected() ? "Y" : " N";
                String wifi = service.getWifiSwap().isConnected() ? "Y" : " N";
                String mdns = service.getWifiSwap().getBonjour().isConnected() ? "Y" : " N";
                message += "Swap { BT: " + bluetooth + ", WiFi: " + wifi + ", mDNS: " + mdns + "}, ";

                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                bluetooth = "N/A";
                if (adapter != null) {
                    Map<Integer, String> scanModes = new HashMap<>(3);
                    scanModes.put(BluetoothAdapter.SCAN_MODE_CONNECTABLE, "CON");
                    scanModes.put(BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, "CON_DISC");
                    scanModes.put(BluetoothAdapter.SCAN_MODE_NONE, "NONE");
                    bluetooth = "\"" + adapter.getName() + "\" - " + scanModes.get(adapter.getScanMode());
                }

                message += "Find { BT: " + bluetooth + ", WiFi: " + wifi + "}";
            }

            Date now = new Date();
            Utils.debugLog("Swap Status", now.getHours() + ":" + now.getMinutes() + ":" + now.getSeconds() + " " + message);

            new Timer().schedule(new TimerTask() {
                                     @Override
                                     public void run() {
                                         new SwapDebug().logStatus();
                                     }
                                 }, 1000
            );
        }
    }

    public void install(@NonNull final App app, @NonNull final Apk apk) {
        localBroadcastManager.registerReceiver(installReceiver,
                Installer.getInstallIntentFilter(apk.getCanonicalUrl()));
        InstallManagerService.queue(this, app, apk);
    }

    private final BroadcastReceiver installReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Installer.ACTION_INSTALL_STARTED:
                    break;
                case Installer.ACTION_INSTALL_COMPLETE:
                    localBroadcastManager.unregisterReceiver(this);

                    showRelevantView(true);
                    break;
                case Installer.ACTION_INSTALL_INTERRUPTED:
                    localBroadcastManager.unregisterReceiver(this);
                    // TODO: handle errors!
                    break;
                case Installer.ACTION_INSTALL_USER_INTERACTION:
                    PendingIntent installPendingIntent =
                            intent.getParcelableExtra(Installer.EXTRA_USER_INTERACTION_PI);

                    try {
                        installPendingIntent.send();
                    } catch (PendingIntent.CanceledException e) {
                        Log.e(TAG, "PI canceled", e);
                    }

                    break;
                default:
                    throw new RuntimeException("intent action not handled!");
            }
        }
    };

}
