package nl.overnightprojects.stravapebble;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.getpebble.android.kit.Constants;
import com.getpebble.android.kit.PebbleKit;
import com.getpebble.android.kit.util.PebbleDictionary;

import java.text.NumberFormat;
import java.util.Locale;

public class NotificationListener extends NotificationListenerService {

    private String TAG = this.getClass().getSimpleName();
    private NLServiceReceiver nlservicereciver;
    private boolean pebbleAppStatus = false;
    private boolean notificationPresent = false;
    private int paceSplitSeconds = 0;
    private float paceSplitPrevDist = 0;
    private String paceSplitPace = "--:--";
    private StatusBarNotification curSbn = null;

    @Override
    public void onCreate() {
        super.onCreate();
        nlservicereciver = new NLServiceReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("com.sanyaas.stravapebble.NOTIFICATION_LISTENER_SERVICE");
        registerReceiver(nlservicereciver,filter);
        PebbleKit.registerReceivedDataHandler(getApplicationContext(), receiver);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(nlservicereciver);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (!"com.strava".equalsIgnoreCase(sbn.getPackageName())){
            return;
        } else if (sbn.getNotification().actions == null || sbn.getNotification().actions.length == 0) {
            return;
        }
//        Log.i(TAG,"**********  onNotificationPosted");
//        Log.i(TAG,"ID :" + sbn.getId() + "\t" + sbn.getNotification().tickerText + "\t" + sbn.getPackageName());
//        Log.i(TAG, sbn.getNotification().toString() + " sbn: " + sbn.toString());

        curSbn = sbn;
        if (!notificationPresent)
        {
            // reset memory variables
            paceSplitSeconds = 0;
            paceSplitPrevDist = 0;
            paceSplitPace = "--:--";

            notificationPresent = true;
            logString("Strava Activity Detected.");
        }

        if(!pebbleAppStatus){
            PebbleKit.startAppOnPebble(getApplicationContext(), Constants.SPORTS_UUID);
            pebbleAppStatus = true;
        }
        if ("stop".equalsIgnoreCase(sbn.getNotification().actions[0].title.toString())) {
            String[] textParts = sbn.getNotification().extras.get("android.title").toString().split(" ");

            String paceString = paceMonitor(textParts[2],textParts[4]);

            // Show a value for duration and distance
            PebbleDictionary dict = new PebbleDictionary();
            dict.addUint8(Constants.SPORTS_LABEL_KEY, (byte)Constants.SPORTS_DATA_PACE);
            dict.addUint8(Constants.SPORTS_UNITS_KEY, (byte) Constants.SPORTS_UNITS_METRIC);
            dict.addString(Constants.SPORTS_TIME_KEY, textParts[2]);
            dict.addString(Constants.SPORTS_DISTANCE_KEY, textParts[4]);
            dict.addString(Constants.SPORTS_DATA_KEY, paceString);
            PebbleKit.sendDataToPebble(getApplicationContext(), Constants.SPORTS_UUID, dict);
        }
        super.onNotificationPosted(sbn);
    }

    // send some text to display in the android app
    private void logString(String msg)
    {
        Intent i = new  Intent("com.sanyaas.stravapebble.NOTIFICATION_LISTENER");
        i.putExtra("notification_event", msg);
        sendBroadcast(i);
        Log.i(TAG, msg);
    }

    private String paceMonitor(String time, String distance) {
        NumberFormat nf = NumberFormat.getInstance(Locale.getDefault());

        float curDist = paceSplitPrevDist;
        try {
            curDist = nf.parse(distance).floatValue();
        }
        catch (Exception e)
        {
            Log.i(TAG, e.getMessage());
        }

        float splitDistance = curDist - paceSplitPrevDist;
        if (splitDistance > 0) {
            String[] timeSplit = time.split(":");
            int parts  = timeSplit.length;
            int currentSeconds = (Integer.parseInt(timeSplit[parts-2]) * 60) + Integer.parseInt(timeSplit[parts-1]);
            if (parts > 2)
            {
                currentSeconds += Integer.parseInt(timeSplit[parts-3]) * 3600;
            }
            float paceSeconds = currentSeconds-paceSplitSeconds;
            int secondsPerKm = Math.round(paceSeconds/splitDistance);

            int minutesPart = secondsPerKm/60;
            int secondsPart = secondsPerKm%60;

            paceSplitPace = String.format("%02d:%02d", minutesPart, secondsPart);
            logString("Dist: " + distance + " Pace: " + paceSplitPace);
            paceSplitSeconds = currentSeconds;
            paceSplitPrevDist = curDist;
            pebbleAppStatus = false; // trigger sports display on pebble watch
        }
        return paceSplitPace;
    }

    @Override
    public void onNotificationRemoved (StatusBarNotification sbn) {
        if ("com.strava".equalsIgnoreCase(sbn.getPackageName()))
        {
            PebbleKit.closeAppOnPebble(getApplicationContext(), Constants.SPORTS_UUID);
            pebbleAppStatus = false;
            notificationPresent = false;
            curSbn = null;
            logString("Strava activity ended.");
        }
    }

    class NLServiceReceiver extends BroadcastReceiver{

        @Override
        public void onReceive(Context context, Intent intent) {
            logString("=====================");
            int i=1;
            for (StatusBarNotification sbn : NotificationListener.this.getActiveNotifications()) {
                logString(i + " " + sbn.getPackageName());
                i++;

            }
            logString("===== Notification List ====");
        }
    }

    PebbleKit.PebbleDataReceiver receiver = new PebbleKit.PebbleDataReceiver(Constants.SPORTS_UUID) {

        @Override
        public void receiveData(Context context, int id, PebbleDictionary data) {
            // Always ACKnowledge the last message to prevent timeouts
            PebbleKit.sendAckToPebble(getApplicationContext(), id);

            Long value = data.getUnsignedIntegerAsLong(Constants.SPORTS_STATE_KEY);
            if(value != null && curSbn != null && curSbn.getNotification().actions.length > 0) {
                int state = value.intValue();

                boolean doToggle = (state == Constants.SPORTS_STATE_RUNNING && "stop".equalsIgnoreCase(curSbn.getNotification().actions[0].title.toString()))
                            || (state == Constants.SPORTS_STATE_PAUSED && "start".equalsIgnoreCase(curSbn.getNotification().actions[0].title.toString()));

                // ignore button press if watch state is out of sync with strava
                if (doToggle) {
                    try {
                        curSbn.getNotification().actions[0].actionIntent.send();
                    } catch (Exception e) {
                        e.printStackTrace();
                        logString("Start/stop failed..");
                        logString(e.getMessage());
                    }
                }
            }
        }

    };
}
