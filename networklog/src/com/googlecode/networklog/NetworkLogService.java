/* (C) 2012 Pragmatic Software
   This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/
 */

package com.googlecode.networklog;

import android.content.ContextWrapper;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.util.Log;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Handler;
import android.os.Messenger;
import android.os.Message;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.RemoteException;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;

import java.util.HashMap;
import java.util.ArrayList;
import java.io.File;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.io.BufferedWriter;
import java.lang.Thread;
import java.lang.Runnable;
import java.lang.reflect.Method;

public class NetworkLogService extends Service {
  ArrayList<Messenger> clients = new ArrayList<Messenger>();
  public static final String TAG = "SPC_NetworkLog";
  static final int NOTIFICATION_ID = "Network Log".hashCode();
  static final int MSG_REGISTER_CLIENT     = 1;
  static final int MSG_UNREGISTER_CLIENT   = 2;
  static final int MSG_UPDATE_NOTIFICATION = 3;
  static final int MSG_BROADCAST_LOG_ENTRY = 4;
  static final int MSG_TOGGLE_FOREGROUND   = 5;
  final Messenger messenger = new Messenger(new IncomingHandler(this));
  boolean has_root = false;
  boolean has_binaries = false;
  public static NetworkLogService instance = null;
  private static Context context;
  public static Handler handler;
  public static String logfileString = "";
  public static Toast toast;
  public static TextView toastTextView;
  public static CharSequence toastText;
  public static boolean toastEnabled;
  public static int toastDuration;
  public static int toastPosition;
  public static int toastDefaultYOffset;
  public static int toastYOffset;
  public static HashMap<String, String> toastBlockedApps;
  public static boolean invertUploadDownload;
  public static boolean behindFirewall;
  public static boolean watchRules;
  public static int watchRulesTimeout;

  private class IncomingHandler extends Handler {
    private Context context;

    public IncomingHandler(Context context) {
      this.context = context;
    }

    @Override
      public void handleMessage(Message msg) {
        Log.d(TAG, "[service] got message: " + msg);

        switch(msg.what) {
          case MSG_REGISTER_CLIENT:
            MyLog.d("[service] registering client " + msg.replyTo);
            clients.add(msg.replyTo);
            break;

          case MSG_UNREGISTER_CLIENT:
            MyLog.d("[service] unregistering client " + msg.replyTo);
            clients.remove(msg.replyTo);
            break;

          case MSG_UPDATE_NOTIFICATION:
            if(MyLog.enabled) {
              MyLog.d("[service] updating notification: " + ((String)msg.obj));
            }
            updateNotification((String)msg.obj);
            break;

          case MSG_TOGGLE_FOREGROUND:
            MyLog.d("[service] toggling service foreground state: " + ((Boolean)msg.obj));
            start_foreground = (Boolean)msg.obj;

            if(start_foreground) {
              startForeground(notification);
            } else {
              stopForeground();
            }
            break;

          case MSG_BROADCAST_LOG_ENTRY:
            MyLog.d("[service] got MSG_BROADCOAST_LOG_ENTRY unexpectedly");
            break;

          default:
            MyLog.d("[service] unhandled message");
            super.handleMessage(msg);
        }
      }
  }

  @Override
    public IBinder onBind(Intent intent) {
      MyLog.d("[service] onBind");
      if(!has_root || !has_binaries) {
        return null;
      } else {
        return messenger.getBinder();
      }
    }

  private static HashMap<String, Integer> logEntriesMap = new HashMap<String, Integer>();
  private ShellCommand loggerCommand;
  private NetworkLogger logger;
  private static String logfile = null;
  private PrintWriter logWriter = null;
  private static NotificationManager nManager;
  private static Notification notification;
  private static int notificationIcon;
  private LogEntry entry;
  private static Boolean start_foreground = true;
  private NetStat netstat = new NetStat();
  private FastParser parser = new FastParser();

  public void startForeground(Notification n) {
    startForeground(NOTIFICATION_ID, n);
  }

  public void stopForeground() {
    stopForeground(true);
  }

  public Notification createNotification() {
    notificationIcon = R.drawable.up0_down0;
    Notification n = new Notification(notificationIcon, getString(R.string.logging_started), System.currentTimeMillis());
    Intent i = new Intent(this, NetworkLog.class);
    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pi = PendingIntent.getActivity(this, 0, i, 0);
    n.setLatestEventInfo(this, getString(R.string.app_name), getString(R.string.logging_active), pi);
    return n;
  }

  static Runnable updateNotificationRunner = new Runnable() {
    public void run() {
      updateNotification();
    }
  };

  public static void updateNotification(int icon) {
    if(instance != null && handler != null) {
      notificationIcon = icon;
      handler.post(updateNotificationRunner);
    }
  }

  public static void updateNotification() {
    if(logfileString.length() > 0) {
      updateNotification(ThroughputTracker.throughputString + " [" + logfileString + "]");
    } else {
      updateNotification(ThroughputTracker.throughputString);
    }
  }

  public static void updateNotification(String text) {
    if(instance == null || context == null || notification == null) {
      return;
    }
    Intent i = new Intent(context, NetworkLog.class);
    i.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    PendingIntent pi = PendingIntent.getActivity(context, 0, i, 0);
    notification.setLatestEventInfo(context, context.getResources().getString(R.string.app_name), text, pi);
    notification.icon = notificationIcon;

    if(start_foreground) {
      nManager.notify(NOTIFICATION_ID, notification);
    }
  }

  private static Runnable showOnlyToastRunnable;
  private static Runnable showToastRunnable;

  public static void showToast(final CharSequence text) {
    if(context == null || handler == null || toastEnabled == false) {
      return;
    }

    if(showToastRunnable == null) {
      showToastRunnable = new Runnable() {
        public void run() {
          if(toast == null) {
            View layout = ((LayoutInflater)context.getSystemService(LAYOUT_INFLATER_SERVICE)).inflate(R.layout.custom_toast, null);
            toastTextView = (TextView) layout.findViewById(R.id.toasttext);
            toast = new Toast(context);
            toastDefaultYOffset = toast.getYOffset();
            toast.setView(layout);
          }

          switch(toastDuration) {
            case 3500:
              toast.setDuration(Toast.LENGTH_LONG);
              break;
            case 7000:
              toast.setDuration(Toast.LENGTH_LONG);

              if(showOnlyToastRunnable == null) {
                showOnlyToastRunnable  = new Runnable() {
                  public void run() {
                    toast.show();
                  }
                };
              }

              handler.postDelayed(showOnlyToastRunnable, 3250);
              break;
            default:
              toast.setDuration(Toast.LENGTH_SHORT);
          }

          switch(toastPosition) {
            case 1:
              toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, toastYOffset);
              break;
            case 2:
              toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, toastYOffset);
              break;
            default:
              toast.setGravity(Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 0, toastDefaultYOffset);
              break;
          }

          toastTextView.setText(android.text.Html.fromHtml(toastText.toString()));
          toast.show();
        }
      };
    }

    toastText = text;
    handler.post(showToastRunnable);
  }

  public boolean hasRoot() {
    return SysUtils.checkRoot(this);
  }

  @Override
    public void onCreate() {
      MyLog.d("[service] onCreate");

      if(!hasRoot()) {
        SysUtils.showError(this, getString(R.string.error_default_title), getString(R.string.error_noroot));
        has_root = false;
        stopSelf();
        return;
      } else {
        has_root = true;
      }

      if(!SysUtils.installBinaries(this)) {
        has_binaries = false;
        stopSelf();
        return;
      } else {
        has_binaries = true;
      }

      if(instance != null) {
        Log.w("NetworkLog", "[service] Last instance destroyed unexpectedly");
      }

      instance = this;
      handler = new Handler();

      if(ApplicationsTracker.installedApps == null) {
        ApplicationsTracker.getInstalledApps(this, null);
      }

      if(NetworkLog.settings == null) {
        NetworkLog.settings = new Settings(this);
      }

      toastEnabled = NetworkLog.settings.getToastNotifications();
      toastDuration = NetworkLog.settings.getToastNotificationsDuration();
      toastPosition = NetworkLog.settings.getToastNotificationsPosition();
      toastYOffset = NetworkLog.settings.getToastNotificationsYOffset();
      toastBlockedApps = SelectToastApps.loadBlockedApps(this);
      invertUploadDownload = NetworkLog.settings.getInvertUploadDownload();
      behindFirewall = NetworkLog.settings.getBehindFirewall();
      //Narseo:
      /*
      watchRules = NetworkLog.settings.getWatchRules();
      watchRulesTimeout = NetworkLog.settings.getWatchRulesTimeout();
      */
      
      watchRules = true;
      watchRulesTimeout = 120000;


      updateLogfileString();
      ThroughputTracker.startUpdater();

      nManager = (NotificationManager)getSystemService(NOTIFICATION_SERVICE);
      notification = createNotification();

      start_foreground = NetworkLog.settings.getStartForeground();

      if(start_foreground) {
        startForeground(notification);
      }
    }

  @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
      Log.e("SPC_NetworkLog2", "[service] onStartCommand");

      if(!has_root || !has_binaries) {
        return Service.START_NOT_STICKY;
      }

      Bundle ext = null;

      if(intent == null) {
        MyLog.d("[service] Service null intent");
      } else {
        ext = intent.getExtras();
      }

      final Bundle extras = ext;
      context = this;

      // run in background thread
      new Thread(new Runnable() {
        public void run() {
          String logfile_from_intent = null;

          if(extras != null) {
            logfile_from_intent = extras.getString("logfile");
            MyLog.d("[service] set logfile: " + logfile_from_intent);
          }

          if(logfile_from_intent == null) {
            logfile_from_intent = NetworkLog.settings.getLogFile();
            Log.i(TAG, "LogFile = "+logfile_from_intent);
          }

          MyLog.d("[service] NetworkLog service starting [" + logfile_from_intent + "]");;

          final String l = logfile_from_intent;

          if(logfile != null) {
            // service already started and has logfile open
          } else {
            logfile = logfile_from_intent;

            // service starting up fresh
            initEntriesMap();
          }

          if(!startLogging()) {
            MyLog.d("[service] start logging error, aborting");
            handler.post(new Runnable() {
              public void run() {
                stopSelf();
              }
            });
          }
        }
      }).start();

      return Service.START_STICKY;
    }

  @Override
    public void onDestroy() {
      MyLog.d("[service] onDestroy");

      stopForeground();
      instance = null;
      context = null;
      handler = null;

      ThroughputTracker.stopUpdater();

      if(NetworkLog.loggingButton != null) {
        NetworkLog.loggingButton.setChecked(false);
      }

      if(has_root && has_binaries) {
        stopLogging();
        Toast.makeText(this, getString(R.string.logging_stopped), Toast.LENGTH_SHORT).show();
      }
    }

  public static NetworkLogService getInstance() { 
    return instance;
  }

  public void initEntriesMap() {
    ArrayList<NetStat.Connection> connections = netstat.getConnections();

    for(NetStat.Connection connection : connections) {
      String mapKey = connection.src + ":" + connection.spt + " -> " + connection.dst + ":" + connection.dpt;

      Log.i(TAG, "NetworkLogService.initEntriesMap() [netstat src-dst] New entry " + connection.uid + " for [" + mapKey + "]");
      

      logEntriesMap.put(mapKey, Integer.valueOf(connection.uid));

      mapKey = connection.dst + ":" + connection.dpt + " -> " + connection.src + ":" + connection.spt;

      if(MyLog.enabled) {
        MyLog.d("[netstat dst-src] New entry " + connection.uid + " for [" + mapKey + "]");
      }

      logEntriesMap.put(mapKey, Integer.valueOf(connection.uid));
    }
  }

  public void parseResult(String result) {
    Log.i(TAG, "NetworkLogService.parseResult () = "+result);
    /*if(MyLog.enabled) {
      MyLog.d("--------------- parsing network entry --------------");
    }*/
    
    double timestamp, timestampProcessing;
    int pos = 0, lastpos, thisEntry, nextEntry, newline, space;
    String in, out, src, dst, proto, uidString;
    int spt, dpt, len, uid, seqNum, ackSeq, win, res, doff, fin, syn, rst, psh, ack, urg, ece, cwr;
    parser.setLine(result.toCharArray(), result.length() - 1);
    
    while((pos = result.indexOf("{NL}", pos)) > -1) {      
      Log.i(TAG, "---- got {NL} at " + pos + " ----");
      

      pos += "{NL}".length(); // skip past "{NL}"

      thisEntry = pos;
      newline = result.indexOf("\n", pos);
      nextEntry = result.indexOf("{NL}", pos);

      if(newline == -1) {
        newline = result.length();
      }

      if(nextEntry != -1 && nextEntry < newline) {
        // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
        pos = newline;
        continue;
      }

      try {
        pos = result.indexOf("IN=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 3);
        in = parser.getString();

        pos = result.indexOf("OUT=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 4);
        out = parser.getString();

        pos = result.indexOf("SRC=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 4);
        src = parser.getString();

        pos = result.indexOf("DST=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 4);
        dst = parser.getString();


        pos = result.indexOf("TIME=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 5);
        timestamp = parser.getDouble();

        
        pos = result.indexOf("TIME_PROC=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 10);
        timestampProcessing = parser.getDouble();

        pos = result.indexOf("LEN=", pos);

        if(pos == -1 || pos > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
          // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 4);
        len = parser.getInt();

        pos = result.indexOf("PROTO=", pos);

        if(pos == -1 || pos > newline) {
           Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        space = result.indexOf(" ", pos);

        if(space == -1 || space > newline) {
           Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
          pos = newline;
          continue;
        }

        parser.setPos(pos + 6);
        proto = parser.getString();
        if (proto.equals("TCP")){
          Log.w("SPC_NetworkLog", "TCP PACKET RECEIVED - Should continue");
        }

        lastpos = pos;
        pos = result.indexOf("SPT=", pos);

        if(pos == -1 || pos > newline) {
          // no SPT field, probably a broadcast packet
          spt = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
             Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          spt = parser.getInt();
        }

        lastpos = pos;
        pos = result.indexOf("DPT=", pos);

        if(pos == -1 || pos > newline) {
          // no DPT field, probably a broadcast packet
          dpt = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            // Log.w("NetworkLog", "Skipping corrupted entry [" + result.substring(thisEntry, newline) + "]");
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          dpt = parser.getInt();
        }

        
        lastpos = pos;
        pos = result.indexOf("SQNUM=", pos);

        if(pos == -1 || pos > newline) {
          // no SQNUM field, probably a broadcast packet
          Log.i("SPC_NetworkLog", "No SQNUM");
          seqNum = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 6);
          seqNum = parser.getInt();
          Log.i("SPC_NetworkLog", "SQNUM="+seqNum);
        }

        lastpos = pos;
        pos = result.indexOf("ACKSEQ=", pos);

        if(pos == -1 || pos > newline) {
          // no SQNUM field, probably a broadcast packet
          Log.i("SPC_NetworkLog", "No ACKSEQ");
          ackSeq = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }
          parser.setPos(pos + 7);
          ackSeq = parser.getInt();
          Log.i("SPC_NetworkLog", "ACKSEQ="+ackSeq);
        }



        lastpos = pos;
        pos = result.indexOf("WIN=", pos);

        if(pos == -1 || pos > newline) {
          // no WIN field, probably a broadcast/UDP packet
          Log.i("SPC_NetworkLog", "No WINDOW_SIZE");
          win = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          win = parser.getInt();
          Log.i("SPC_NetworkLog", "WINDOW_SIZE="+win);
        }


        lastpos = pos;
        pos = result.indexOf("RES=", pos);

        if(pos == -1 || pos > newline) {
          // no RES field, probably a broadcast/UDP packet
          Log.i("SPC_NetworkLog", "No RES");
          res = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          res = parser.getInt();
          Log.i("SPC_NetworkLog", "RES="+res);
        }


        lastpos = pos;
        pos = result.indexOf("DOFF=", pos);

        if(pos == -1 || pos > newline) {
          // no DOFF field, probably a broadcast/UDP packet
          doff = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 5);
          doff = parser.getInt();
          Log.i("SPC_NetworkLog", "DOFF="+doff);
        }



        lastpos = pos;
        pos = result.indexOf("FIN=", pos);

        if(pos == -1 || pos > newline) {
          // no FIN field, probably a broadcast/UDP packet
          fin = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          fin = parser.getInt();
          Log.i("SPC_NetworkLog", "FIN="+fin);
        }



        lastpos = pos;
        pos = result.indexOf("SYN=", pos);

        if(pos == -1 || pos > newline) {
          // no SYN field, probably a broadcast/UDP packet
          syn = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          syn = parser.getInt();
          Log.i("SPC_NetworkLog", "SYN="+syn);
        }




        lastpos = pos;
        pos = result.indexOf("RST=", pos);

        if(pos == -1 || pos > newline) {
          // no RST field, probably a broadcast/UDP packet
          rst = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          rst = parser.getInt();
          Log.i("SPC_NetworkLog", "RST="+rst);
        }



        lastpos = pos;
        pos = result.indexOf("PSH=", pos);

        if(pos == -1 || pos > newline) {
          // no PSH field, probably a broadcast/UDP packet
          psh = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }

          parser.setPos(pos + 4);
          psh = parser.getInt();
          Log.i("SPC_NetworkLog", "PSH="+psh);
        }


        lastpos = pos;
        pos = result.indexOf("ACK=", pos);

        if(pos == -1 || pos > newline) {
          // no ACK field, probably a broadcast/UDP packet
          Log.i("SPC_NetworkLog", "No ACK");
          ack = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }
          parser.setPos(pos + 4);
          ack = parser.getInt();
          Log.i("SPC_NetworkLog", "ACK="+ack);
        }


        lastpos = pos;
        pos = result.indexOf("URG=", pos);

        if(pos == -1 || pos > newline) {
          // no URG field, probably a broadcast packet
          Log.i("SPC_NetworkLog", "No URG");
          urg = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }
          parser.setPos(pos + 4);
          urg = parser.getInt();
          Log.i("SPC_NetworkLog", "URG="+urg);
        }


        lastpos = pos;
        pos = result.indexOf("ECE=", pos);

        if(pos == -1 || pos > newline) {
          // no ECE field, probably a broadcast packet
          ece = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }
          parser.setPos(pos + 4);
          ece = parser.getInt();
          Log.i("SPC_NetworkLog", "ECE="+ece);
        }


        lastpos = pos;
        pos = result.indexOf("CWR=", pos);

        if(pos == -1 || pos > newline) {
          // no CWR field, probably a broadcast packet
          cwr = 0;
          pos = lastpos;
        } else {
          space = result.indexOf(" ", pos);

          if(space == -1 || space > newline) {
            pos = newline;
            continue;
          }
          parser.setPos(pos + 4);
          cwr = parser.getInt();
          Log.i("SPC_NetworkLog", "CWR="+cwr);
        }

    

        lastpos = pos;
        pos = result.indexOf("UID=", pos);

        //Narseo: comment. Nflog.c does not report PID for incoming packets.
        //The following code assigns value "-1" to be post-processed by
        //LogFragment.onNewLogEntry() which later 
        //invokes ApplicationsTracker.uidMap.get()
        if(pos == -1 || pos > newline) {
          uid = -1;
          uidString = "-1";
          pos = lastpos;
        } else {
          parser.setPos(pos + 4);
          uid = parser.getInt();
          parser.setPos(pos + 4);
          uidString = parser.getString();
        }
      } catch(Exception e) {
        Log.e("NetworkLog", "Bad data for: [" + result.substring(thisEntry, newline) + "]", e);
        pos = newline;
        continue;
      }

      Log.d(TAG, "Setting map key: src=[" + src + "] spt=" + spt + " dst=[" + dst + "] dpt=" + dpt);

      String srcDstMapKey = src + ":" + spt + "->" + dst + ":" + dpt;
      String dstSrcMapKey = dst + ":" + dpt + "->" + src + ":" + spt;

      Log.d(TAG, "Getting right PID. Checking entry for " + uid + " " + srcDstMapKey + " and " + dstSrcMapKey);
      

      Integer srcDstMapUid = logEntriesMap.get(srcDstMapKey);
      Integer dstSrcMapUid = logEntriesMap.get(dstSrcMapKey);

      if(uid < 0) {
        // Unknown uid, retrieve from entries map
        if(MyLog.enabled) {
          Log.d(TAG, "Unknown uid");
        }

        if(srcDstMapUid == null || dstSrcMapUid == null) {
          // refresh netstat and try again
          Log.d(TAG, "Refreshing netstat ...");
          
          initEntriesMap();
          srcDstMapUid = logEntriesMap.get(srcDstMapKey);
          dstSrcMapUid = logEntriesMap.get(dstSrcMapKey);
        }

        if(srcDstMapUid == null) {
          Log.d(TAG, "[src-dst] No entry uid for " + uid + " [" + srcDstMapKey + "]");
          

          if(uid == -1) {
            if(dstSrcMapUid != null) {
              Log.d(TAG, "[dst-src] Reassigning kernel packet -1 to " + dstSrcMapUid);
              
              uid = dstSrcMapUid;
              uidString = StringPool.get(dstSrcMapUid);
            } else {
              Log.d(TAG, "[src-dst] New kernel entry -1 for [" + srcDstMapKey + "]");
              
              srcDstMapUid = uid;
              logEntriesMap.put(srcDstMapKey, srcDstMapUid);
            }
          } else {
            Log.d(TAG, "[src-dst] New entry " + uid + " for [" + srcDstMapKey + "]");
            
            srcDstMapUid = uid;
            logEntriesMap.put(srcDstMapKey, srcDstMapUid);
          }
        } else {
          Log.d(TAG, "[src-dst] Found entry uid " + srcDstMapUid + " for " + uid + " [" + srcDstMapKey + "]");
          
          uid = srcDstMapUid;
          uidString = StringPool.get(srcDstMapUid);
        }

        if(dstSrcMapUid == null) {
          Log.d(TAG, "[dst-src] No entry uid for " + uid + " [" + dstSrcMapKey + "]");
          

          if(uid == -1) {
            if(srcDstMapUid != null) {
              Log.d(TAG, "[src-dst] Reassigning kernel packet -1 to " + srcDstMapUid);
              
              uid = srcDstMapUid;
              uidString = StringPool.get(srcDstMapUid);
            } else {
              Log.d(TAG, "[dst-src] New kernel entry -1 for [" + dstSrcMapKey + "]");
              
              dstSrcMapUid = uid;
              logEntriesMap.put(dstSrcMapKey, dstSrcMapUid);
            }
          } else {
            Log.d(TAG, "[dst-src] New entry " + uid + " for [" + dstSrcMapKey + "]");
            
            dstSrcMapUid = uid;
            logEntriesMap.put(dstSrcMapKey, dstSrcMapUid);
          }
        } else {
          Log.d(TAG, "[dst-src] Found entry uid " + dstSrcMapUid + " for " + uid + " [" + dstSrcMapKey + "]");
          uid = dstSrcMapUid;
          uidString = StringPool.get(dstSrcMapUid);
        }
      } else {
        Log.d(TAG, "Known uid");
        

        if(srcDstMapUid == null || dstSrcMapUid == null || srcDstMapUid != uid || dstSrcMapUid != uid) {
          Log.d(TAG, "Updating uid " + uid + " to netstat map for " + srcDstMapKey + " and " + dstSrcMapKey);
          logEntriesMap.put(srcDstMapKey, uid);
          logEntriesMap.put(dstSrcMapKey, uid);
        }
      }

      //Narseo: I addedd all the changes to extract the tcp header values.
      entry = new LogEntry();
      entry.uid = uid;
      entry.uidString = uidString;
      entry.in = in;
      entry.out = out;
      entry.src = src;
      entry.spt = spt;
      entry.dst = dst;
      entry.dpt = dpt;
      entry.proto = proto;
      entry.len = len;
      entry.seqNum = seqNum;
      entry.ackSeq = ackSeq;
      entry.win = win;
      entry.res = res;
      entry.doff = doff;
      entry.fin = fin;
      entry.syn = syn;
      entry.rst = rst;
      entry.psh = psh;
      entry.ack = ack;
      entry.urg = urg;
      entry.ece = ece;
      entry.cwr = cwr;

      entry.timestamp = (long) (timestamp*1000.0);  //In ms. Truncating to 0
      entry.timestampProcessing = (long) (timestampProcessing*1000.0);
      
      
      notifyNewEntry(entry);
    }
  }

  public void notifyNewEntry(LogEntry entry) {

/* Narseo: not printing here yet as PID has not been identified. Printing moved
 * to logFragment
    // check if logfile needs to be opened and that external storage is available
    if(logWriter == null) {
      if(android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
        try {
          logWriter = new PrintWriter(new BufferedWriter(new FileWriter(logfile, true)), true);
          Log.d(TAG, "Opened " + logfile + " for logging");
        } catch(final Exception e) {
          Log.e(TAG, "Exception opening logfile [" + logfile +"]", e);
          handler.post(new Runnable() {
            public void run() {
              SysUtils.showError(context, getString(R.string.error_default_title), getString(R.string.error_openlogfile) + e.getMessage());
            }
          });
          return;
        }
      } else {
        Log.w("NetworkLog", "External storage " + logfile + " not available");
      }
    }

    if(!entry.isValid()) {
      return;
    }

    // log entry to logfile
    if(logWriter != null) {
      Log.i("SPC_NetworkLog", "Writing entry in log: "+logfile+" -- "+ entry.timestamp + "," + entry.in + "," + entry.out + "," + entry.uidString + "("+entry.uid+")," + entry.src + "," + entry.spt + "," + entry.dst + "," + entry.dpt + "," + entry.len + "," + entry.proto);

      logWriter.println( "NEW ENTRY: "+entry.uidString+","+entry.timestamp + "," + entry.in + "," + entry.out + "," + entry.uid + "," + entry.src + "," + entry.spt + "," + entry.dst + "," + entry.dpt + "," + entry.len + "," + entry.proto);
    }

  */

    for(int i = clients.size() - 1; i >= 0; i--) {
      try {
        Log.i(TAG, "[service] Sending entry to " + clients.get(i));
        
        clients.get(i).send(Message.obtain(null, MSG_BROADCAST_LOG_ENTRY, entry));
      } catch(RemoteException e) {
        // client dead
        MyLog.d("[service] Dead client " + clients.get(i));
        clients.remove(i);
      }
    }

    Log.i(TAG, "NetworkLogService - Invoking ThroughputTracker update "+entry);
    ThroughputTracker.updateEntry(entry);
  }

  public void stopLogger() {
    if(logger != null) {
      logger.stop();
    }
  }

  public void closeLogfile() {
    if(logWriter != null) {
      logWriter.close();
      logWriter = null;
    }
  }

  public void killLoggerCommand() {
    if(Iptables.targets == null && Iptables.getTargets(this) == false) {
      return;
    }

    String binary;
    if(Iptables.targets.get("NFLOG") != null) {
      binary = SysUtils.getNflogBinary();
      if(binary == null) {
        return;
      }
    } else if(Iptables.targets.get("LOG") != null) {
      binary = SysUtils.getGrepBinary();
      if(binary == null) {
        return;
      }
    } else {
      return;
    }

    String binaryPath = getFilesDir().getAbsolutePath() + File.separator + binary;
    String grepPath = getFilesDir().getAbsolutePath() + File.separator + SysUtils.getGrepBinary();

    synchronized(NetworkLog.SCRIPT) {
      String scriptFile = new ContextWrapper(this).getFilesDir().getAbsolutePath() + File.separator + NetworkLog.SCRIPT;
      PrintWriter script;
      Log.i("GETTING_SCRIPT_PATH", scriptFile);


      try {
        script = new PrintWriter(new BufferedWriter(new FileWriter(scriptFile)));
        script.println("ps | " + grepPath + " " + binaryPath);
        script.close();
        script = null;
      } catch(java.io.IOException e) {
        Log.e("NetworkLog", "Exception killing logger", e);
        return;
      }

      ShellCommand command = new ShellCommand(new String[] { "su", "-c", "sh " + scriptFile }, "FindLogger");
      command.start(false);

      String string, cmd = "";
      int pid = 0, token, pos, space;
      boolean error = false;
      boolean gotKill = false;

      while(true) {
        String line = command.readStdoutBlocking();

        if(line == null) {
          break;
        }

        // MyLog.d("ps - parsing line [" + line + "]");

        token = 0;
        pos = 0;
        error = false;

        // get tokens
        while(true) {
          space = line.indexOf(' ', pos);

          if(space == -1) {
            // last token
            cmd = line.substring(pos, line.length() - 1);
            break;
          }

          string = line.substring(pos, space);

          try {
            switch(token) {
              case 1:
                pid = Integer.parseInt(string);
                break;
              default:
            }
          } catch(NumberFormatException e) {
            error = true;
            break;
          } catch(ArrayIndexOutOfBoundsException e) {
            error = true;
            break;
          } catch(Exception e) {
            error = true;
            Log.d("NetworkLog", "Unexpected exception", e);
            break;
          }

          token++;
          
          pos = space + 1;

          while(line.charAt(pos) == ' ') {
            pos++;
          }
        }

        if(error == true) {
          continue;
        }

        // MyLog.d("cmd: " + cmd + "; pid: " + pid);
        if(cmd.contains(binaryPath)) {
          Log.d("NetworkLog", "Killing tracker " + pid);
          gotKill = true;

          try {
            if(script == null) {
              script = new PrintWriter(new BufferedWriter(new FileWriter(scriptFile)));
            }

            script.println("kill " + pid);
          } catch(Exception e) {
            Log.e("NetworkLog", "Exception killing logger", e);
            return;
          }
        }
      }

      if(gotKill) {
        try {
          script.close();
        } catch (Exception e) {
          Log.e("NetworkLog", "Exception killing logger", e);
          return;
        }

        new ShellCommand(new String[] { "su", "-c", "sh " + scriptFile }, "KillLogger").start(true);
      }
    }
  }

  public boolean startLoggerCommand() {
    Log.d("SPC_NetworkLog", "NetworkLogService.startLoggerCommand");
    String binary;

    if(Iptables.targets == null && Iptables.getTargets(this) == false) {
      return false;
    }

    if(Iptables.targets.get("NFLOG") != null) {
      binary = SysUtils.getNflogBinary();
      if(binary == null) {
        return false;
      }
    } else if(Iptables.targets.get("LOG") != null) {
      binary = SysUtils.getGrepBinary();
      if(binary == null) {
        return false;
      }
    } else {
      Log.e("NetworkLog", "No supported iptables targets available");
      return false;
    }

    synchronized(NetworkLog.SCRIPT) {
      String scriptFile = new ContextWrapper(this).getFilesDir().getAbsolutePath() + File.separator + NetworkLog.SCRIPT;

      String binaryPath = getFilesDir().getAbsolutePath() + File.separator + binary;

      try {
        PrintWriter script = new PrintWriter(new BufferedWriter(new FileWriter(scriptFile)));
        if(Iptables.targets.get("NFLOG") != null) {
          script.println(binaryPath + " 0");
        } else if(Iptables.targets.get("LOG") != null) {
          script.println(binaryPath + " {NL} /proc/kmsg");
        }
        script.close();
      } catch(java.io.IOException e) {
        e.printStackTrace();
      }

      MyLog.d("Starting iptables logger");

      loggerCommand = new ShellCommand(new String[] { "su", "-c", "sh " + scriptFile }, "NetworkLogger");
      final String error = loggerCommand.start(false);

      if(error != null) {
        SysUtils.showError(this, getString(R.string.error_default_title), error);
        return false;
      } else {
        return true;
      }
    }
  }

  public boolean startLogging() {
    
    Log.d("SPC_NetworkLog", "adding logging rules");
    killLoggerCommand();    
    if(!Iptables.addRules(this)) {
      return false;
    }

    if(!startLoggerCommand()) {
      return false;
    }

    logger = new NetworkLogger();
    new Thread(logger, "NetworkLogger").start();

    startWatchingExternalStorage();
    startWatchingRules();

    return true;
  }

  public void stopLogging() {
    stopWatchingRules();
    Iptables.removeRules(this);
    stopWatchingExternalStorage();
    stopLogger();
    closeLogfile();
    killLoggerCommand();
  }

  public class NetworkLogger implements Runnable {
    boolean running = false;

    public void stop() {
      running = false;
    }

    public void run() {
      Log.d("NetworkLog", "Network logger " + this + " starting");
      String result;
      running = true;

      while(true) {
        while(running && loggerCommand.checkForExit() == false) {
          if(loggerCommand.stdoutAvailable()) {
            result = loggerCommand.readStdout();
          } else {
            try {
              Thread.sleep(500);
            }
            catch(Exception e) {
              Log.d("NetworkLog", "NetworkLogger exception while sleeping", e);
            }

            continue;
          }

          Log.i(TAG, " - NetworkLogService.run() "+result+" -- should be parsed after");

          if(running == false) {
            break;
          }

          if(result == null) {
            Log.d("NetworkLog", "Network logger " + this + " read null; exiting");
            break;
          }
          

          parseResult(result);
        }

        if(running != false) {
          Log.d("SPC_NetworkLog", "Network logger " + this + " terminated unexpectedly, restarting in 10 seconds");
          try {
            Thread.sleep(10000);
          } catch (Exception e) {
            // ignored
          }
          if(!startLoggerCommand()) {
            running = false;
          }
        } else {
          Log.d("SPC_NetworkLog", "Network logger " + this + " reached end of loop; exiting");
          break;
        }
      }
    }
  }

  public static void updateLogfileString() {
    if(context == null) {
      return;
    }

    try {
      String file = logfile;
      if(file == null) {
        file = NetworkLog.settings.getLogFile();
      }
      logfileString = StringUtils.formatToBytes(new File(file).length()) + "B";
    } catch(Exception e) {
      logfileString = context.getResources().getString(R.string.logfile_bad) + e.getMessage();
    }

    if(instance != null && handler != null) {
      handler.post(updateNotificationRunner);
    }

    if(NetworkLog.handler != null) {
      NetworkLog.handler.post(NetworkLog.updateStatusRunner);
    }
  }

  BroadcastReceiver mExternalStorageReceiver = null;

  void updateExternalStorageState() {
    if(!android.os.Environment.getExternalStorageState().equals(android.os.Environment.MEDIA_MOUNTED)) {
      // internal storage not mounted
      if(logWriter != null) {
        MyLog.d("Stopping logfile logging");
        logWriter.close();
        logWriter = null;
      }
    }
  }

  void startWatchingExternalStorage() {
    if(mExternalStorageReceiver == null) {
      mExternalStorageReceiver = new BroadcastReceiver() {
        @Override
          public void onReceive(Context context, Intent intent) {
            Log.i("NetworkLog", "External storage: " + intent.getData());
            updateExternalStorageState();
          }
      };
    }

    IntentFilter filter = new IntentFilter();
    filter.addAction(Intent.ACTION_MEDIA_MOUNTED);
    filter.addAction(Intent.ACTION_MEDIA_REMOVED);
    registerReceiver(mExternalStorageReceiver, filter);
    updateExternalStorageState();
  }

  void stopWatchingExternalStorage() {
    try {
      unregisterReceiver(mExternalStorageReceiver);
    } catch (Exception e) {
      // ignored
    }
  }

  public class RulesWatcher extends Thread {
    boolean running = false;

    public RulesWatcher() {
      setName("RulesWatcher");
    }

    public void stopRunning() {
      running = false;
      interrupt();
    }

    @Override
      public void run() {
        String md5sum;
        String lastMd5sum = null;

        running = true;
        while(running) {
          try {
            Thread.sleep(watchRulesTimeout);
          } catch(Exception e) {
            // ignored
          }

          if(context == null || running == false) {
            break;
          }

          md5sum = MD5Sum.digestString(Iptables.getRules(context));

          if(lastMd5sum == null) {
            lastMd5sum = md5sum;
          } else {
            if(!md5sum.equals(lastMd5sum)) {
              Log.i("NetworkLog", "Iptables rules changed, reapplying Network Log rules");
              Iptables.removeRules(context);
              Iptables.addRules(context);
              lastMd5sum = MD5Sum.digestString(Iptables.getRules(context));
            }
          }
        }
      }
  }

  public static RulesWatcher rulesWatcher;

  void startWatchingRules() {
    stopWatchingRules();
    //Narseo: removed to force IP tables watchdog
    //if(watchRules) {
      rulesWatcher = new RulesWatcher();
      rulesWatcher.start();
    //}
  }

  void stopWatchingRules() {
    if(rulesWatcher != null) {
      rulesWatcher.stopRunning();
      rulesWatcher = null;
    }
  }
}
