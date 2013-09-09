/* (C) 2012 Pragmatic Software
   This Source Code Form is subject to the terms of the Mozilla Public
   License, v. 2.0. If a copy of the MPL was not distributed with this
   file, You can obtain one at http://mozilla.org/MPL/2.0/
 */

package com.googlecode.networklog;

import android.util.Log;

import java.lang.Runtime;
import java.lang.Process;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.lang.Thread;
import java.util.Arrays;

public class ShellCommand {
  Runtime rt;
  String[] command;
  String tag = "";
  public static final String TAG = "SPC_NetworkLog";
  Process process;
  BufferedReader stdout;
  public int exit;

  public ShellCommand(String[] command, String tag) {
    this(command);
    this.tag = tag;
  }

  public ShellCommand(String[] command) {
    this.command = command;
    rt = Runtime.getRuntime();
  }

  public String start(boolean waitForExit) {
    Log.d(TAG, "ShellCommand: starting [" + tag + "] " + Arrays.toString(command));

    try {
      process = new ProcessBuilder()
        .command(command)
        .redirectErrorStream(true)
        .start();

      stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
    } catch(Exception e) {
      Log.e("NetworkLog", "Failure starting shell command [" + tag + "]", e);
      return e.getCause().getMessage();
    }

    if(waitForExit) {
      waitForExit();
    }
    return null;
  }

  public void waitForExit() {
    while(checkForExit() == false) {
      if(stdoutAvailable()) {
        if(MyLog.enabled) {
          MyLog.d("ShellCommand waitForExit [" + tag + "] discarding read: " + readStdout());
        }
      } else {
        try {
          Thread.sleep(100);
        } catch(Exception e) {
          Log.d("NetworkLog", "waitForExit", e);
        }
      }
    }
  }

  public void finish() {
    MyLog.d("ShellCommand: finishing [" + tag + "] " + Arrays.toString(command));

    try {
      if(stdout != null) {
        stdout.close();
      }
    } catch(Exception e) {
      Log.e("NetworkLog", "Exception finishing [" + tag + "]", e);
    }

    process.destroy();
    process = null;
  }

  public boolean checkForExit() {
    try {
      exit = process.exitValue();
      MyLog.d("ShellCommand exited: [" + tag + "] exit " + exit);
    } catch(Exception IllegalThreadStateException) {
      return false;
    }

    finish();
    return true;
  }

  public boolean stdoutAvailable() {
    try {
      /*
      if(MyLog.enabled) {
        MyLog.d("stdoutAvailable [" + tag + "]: " + stdout.ready());
      }
      */
      Log.i("SPC_NetworkLog", "ShellCommand.stdoutAvailable()");
      return stdout.ready();
    } catch(java.io.IOException e) {
      Log.e("SPC_NetworkLog", "stdoutAvailable error", e);
      return false;
    }
  }

  public String readStdoutBlocking() {
    if(MyLog.enabled) {
      Log.e(TAG, "readStdoutBlocking [" + tag + "]");
    }
    String line;

    if(stdout == null) {
      return null;
    }

    try {
      line = stdout.readLine();
    } catch(Exception e) {
      Log.e( "SPC_NetworkLog", "readStdoutBlocking error"+ e.getMessage());
      return null;
    }

    if(MyLog.enabled) {
      Log.e(TAG, "readStdoutBlocking [" + tag + "] return [" + line + "]");
    }

    if(line == null) {
      return null;
    }
    else {
      return line + "\n";
    }
  }

  public String readStdout() {
    Log.e("SPC_NetworkLog", "ShellCommand.readStdout [" + tag + "]");
    

    if(stdout == null) {
      return null;
    }

    try {
      if(stdout.ready()) {
        String line = stdout.readLine();
        Log.e("SPC_NetworkLog", "ShellCommand.readStdout() - Read: "+line);
        if(MyLog.enabled) {
          Log.e(TAG, "read line: [" + line + "]");
        }

        if(line == null) {
          return null;
        }
        else {
          return line + "\n";
        }
      } else {
        Log.e(TAG, "readStdout [" + tag + "] no data");
        return "";
      }
    } catch(Exception e) {
      Log.e(TAG, "readStdout error", e);
      return null;
    }
  }
}
