package com.bq.robotic.sendhextoinobybluetooth.app;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.provider.OpenableColumns;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AlertDialog;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.bq.robotic.droid2ino.activities.BaseBluetoothSendOnlyActivity;
import com.bq.robotic.droid2ino.utils.Droid2InoConstants;
import com.bq.robotic.protocolstk500v1library.protocol_stk_500_v1.Logger;
import com.bq.robotic.protocolstk500v1library.protocol_stk_500_v1.STK500v1;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.List;

import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

public class MainActivity extends BaseBluetoothSendOnlyActivity
   implements EasyPermissions.PermissionCallbacks {

    private final int PICKFILE_RESULT_CODE = 1;
    private String fileName;
    private Uri fileUri;
    private OutputStream outStream = null;
    private InputStream inputStream = null;

    private static final String[] FILE_PROJECTION={
       OpenableColumns.DISPLAY_NAME,
       OpenableColumns.SIZE
    };

    private Context ctx;
    private Logger log;

   // Permissions
   // Location permission is now needed in order to scan for near bluetooth devices
   private static final int RC_LOCATION_PERM = 124;

    // Debugging
    private static final String LOG_TAG = "MainActivity";

//    private static final String hexData = ":100000000C9461000C947E000C947E000C947E0095" +
//                        ":100010000C947E000C947E000C947E000C947E0068" +
//                        ":100020000C947E000C947E000C947E000C947E0058" +
//                        ":100030000C947E000C947E000C947E000C947E0048" +
//                        ":100040000C94AC000C947E000C947E000C947E000A" +
//                        ":100050000C947E000C947E000C947E000C947E0028" +
//                        ":100060000C947E000C947E00000000002400270009" +
//                        ":100070002A0000000000250028002B0000000000DE" +
//                        ":1000800023002600290004040404040404040202DA" +
//                        ":100090000202020203030303030301020408102007" +
//                        ":1000A0004080010204081020010204081020000012" +
//                        ":1000B0000007000201000003040600000000000029" +
//                        ":1000C000000011241FBECFEFD8E0DEBFCDBF11E08E" +
//                        ":1000D000A0E0B1E0EAE5F4E002C005900D92A230A4" +
//                        ":1000E000B107D9F711E0A2E0B1E001C01D92AB3039" +
//                        ":1000F000B107E1F70E949D000C942B020C940000C4" +
//                        ":100100008091000161E00E94D60168EE73E080E01A" +
//                        ":1001100090E00E942F018091000160E00E94D601D2" +
//                        ":1001200068EE73E080E090E00E942F0108958091D6" +
//                        ":10013000000161E00E9497010895CF93DF930E9430" +
//                        ":10014000F4000E949700C0E0D0E00E948000209759" +
//                        ":10015000E1F30E940000F9CF1F920F920FB60F92A9" +
//                        ":1001600011242F933F938F939F93AF93BF938091CD" +
//                        ":10017000060190910701A0910801B0910901309109" +
//                        ":100180000A010196A11DB11D232F2D5F2D3720F0EF" +
//                        ":100190002D570196A11DB11D20930A0180930601E0" +
//                        ":1001A00090930701A0930801B09309018091020187" +
//                        ":1001B00090910301A0910401B09105010196A11D48" +
//                        ":1001C000B11D8093020190930301A0930401B093A9" +
//                        ":1001D0000501BF91AF919F918F913F912F910F900A" +
//                        ":1001E0000FBE0F901F901895789484B5826084BDDF" +
//                        ":1001F00084B5816084BD85B5826085BD85B581602B" +
//                        ":1002000085BDEEE6F0E0808181608083E1E8F0E08A" +
//                        ":100210001082808182608083808181608083E0E8B9" +
//                        ":10022000F0E0808181608083E1EBF0E08081846098" +
//                        ":100230008083E0EBF0E0808181608083EAE7F0E09A" +
//                        ":1002400080818460808380818260808380818160FE" +
//                        ":1002500080838081806880831092C1000895EF922E" +
//                        ":10026000FF920F931F93CF93DF937B018C013FB7D6" +
//                        ":10027000F8948091020190910301A0910401B09142" +
//                        ":10028000050126B5A89B05C02F3F19F00196A11DB9" +
//                        ":10029000B11D3FBFBA2FA92F982F8827820F911D1C" +
//                        ":1002A000A11DB11D52E0880F991FAA1FBB1F5A95AF" +
//                        ":1002B000D1F7EC0130C00E942A023FB7F894809138" +
//                        ":1002C000020190910301A0910401B091050126B5AE" +
//                        ":1002D000A89B05C02F3F19F00196A11DB11D3FBF7E" +
//                        ":1002E000BA2FA92F982F8827820F911DA11DB11D0C" +
//                        ":1002F00032E0880F991FAA1FBB1F3A95D1F78C1BBC" +
//                        ":100300009D0B885E9340B8F20894E108F10801095A" +
//                        ":100310001109C851DC4FE114F1040105110559F62A" +
//                        ":10032000DF91CF911F910F91FF90EF900895CF93A0" +
//                        ":10033000DF93482F50E0CA0186569F4FFC0134914D" +
//                        ":100340004A575F4FFA018491882369F190E0880F42" +
//                        ":10035000991FFC01E859FF4FA591B491FC01EE589B" +
//                        ":10036000FF4FC591D491662351F42FB7F8948C9127" +
//                        ":10037000932F909589238C93888189230BC06230B9" +
//                        ":1003800061F42FB7F8948C91932F909589238C93D7" +
//                        ":100390008881832B88832FBF06C09FB7F8948C91E8" +
//                        ":1003A000832B8C939FBFDF91CF910895482F50E00E" +
//                        ":1003B000CA0182559F4FFC012491CA0186569F4F66" +
//                        ":1003C000FC0194914A575F4FFA013491332309F4A9" +
//                        ":1003D00040C0222351F1233071F0243028F4213021" +
//                        ":1003E000A1F0223011F514C02630B1F02730C1F051" +
//                        ":1003F0002430D9F404C0809180008F7703C08091AD" +
//                        ":1004000080008F7D8093800010C084B58F7702C0FC" +
//                        ":1004100084B58F7D84BD09C08091B0008F7703C003" +
//                        ":100420008091B0008F7D8093B000E32FF0E0EE0F5D" +
//                        ":10043000FF1FEE58FF4FA591B4912FB7F894662394" +
//                        ":1004400021F48C919095892302C08C91892B8C93F7" +
//                        ":0A0450002FBF08950895F894FFCF20" +
//                        ":02045A000D0093" +
//                        ":00000001FF";

    private Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ctx = getBaseContext();
        log = new Log(this, ctx);

       requestPermissions();
    }

   /**
     * Callback for the changes of the connection status
     */
    @Override
    public void onConnectionStatusUpdate(int connectionState) {
        switch (connectionState) {
            case Droid2InoConstants.STATE_CONNECTED:
                programBoard();
                setStatus(getString(R.string.title_connected_to, mConnectedDeviceName));

                break;

            case Droid2InoConstants.STATE_CONNECTING:
                setStatus(R.string.title_connecting);
                break;

            case Droid2InoConstants.STATE_LISTEN:

            case Droid2InoConstants.STATE_NONE:
                setStatus(R.string.title_not_connected);
                outStream = null;
                inputStream = null;

                break;
        }
    }


    /**
     * Put the status of the connection in the action bar
     * @param resId text id for the status
     */
    private void setStatus(int resId) {
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setSubtitle(resId);
    }


    /**
     * Put the status of the connection in the action bar
     * @param subTitle text for the status
     */
    private void setStatus(CharSequence subTitle) {
        final ActionBar actionBar = getSupportActionBar();
        actionBar.setSubtitle(subTitle);
    }


    /**
     * Method to search for a file in the mobile device
     * @param v view clicked
     */
    public void onBrowseClicked(View v) {
        Intent fileIntent;
        if (Build.VERSION.SDK_INT >= 19) {
            fileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        } else {
            fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
            fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        }

        fileIntent.setType("*/*");
        try {
            startActivityForResult(fileIntent, PICKFILE_RESULT_CODE);
        } catch (ActivityNotFoundException e) {
            log.logcat("No activity can handle picking a file. Showing alternatives.", "e");
            log.makeToast("No app can handle picking a file. Install one before using this app");
        }
    }


    /**
     * Result for the picking file app
     * @param requestCode
     * @param resultCode
     * @param data
     */
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        // TODO Fix no activity available
        if (data == null)
            return;
        switch (requestCode) {
            case PICKFILE_RESULT_CODE:
                if (resultCode == RESULT_OK) {
                    fileUri = data.getData();

                    //FilePath is your file as a string
                    Cursor cursor = null;
                    try {
                        cursor = getContentResolver().query(fileUri,
                           FILE_PROJECTION, null, null, null);
                        if (cursor != null && cursor.moveToFirst()) {
                            int fileNameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                            fileName = cursor.getString(fileNameIndex);
                            log.logcat("File name is " + fileName, "d");
                        }

                    } catch (Exception e) {
                        if (cursor != null) {
                            cursor.close();
                        }
                        fileName = getString(R.string.filename_error);
                        log.logcat("Error trying to obtain " +
                           "the file name: " + e, "e");
                    }

                    ((TextView) findViewById(R.id.file_to_send)).setText(fileName);
                    findViewById(R.id.send_button).setEnabled(true);
                }
        }
    }


    /**
     * Pressed the button for sending the program to the microcontroller board in order to install
     * the program in it
     * @param v view clicked
     */
    public void onSendClicked(View v) {

        if(fileName == null || fileName.equals("")) {
            Toast.makeText(this, "The filepath is null or empty", Toast.LENGTH_SHORT).show();
            return;

        } else if (!fileName.endsWith(".hex")) {
            Toast.makeText(this, "The file is not valid. It must be a .hex file", Toast.LENGTH_SHORT).show();
            return;
        }

        // Connect to the micro board bluetooth module
        requestDeviceConnection();
    }


    /**
     * Program the microcontroller board
     */
    private void programBoard() {
        findViewById(R.id.progress_bar).setVisibility(View.VISIBLE);

        new Thread(new Runnable() {

            @Override
            public void run() {
                BufferedReader br = null;
                StringBuilder hexData = new StringBuilder();

                try {

                    br = new BufferedReader(
                       new InputStreamReader(getContentResolver().openInputStream(fileUri)));

                    String line;

                    while ((line = br.readLine()) != null) {
                        hexData.append(line);
                    }


                } catch (FileNotFoundException e) {
                    log.logcat("Exception in onSendClicked: " + e, "e");
                } catch (IOException e) {
                    log.logcat("Exception in onSendClicked: " + e, "e");
                } catch (Exception e) {
                    log.logcat("Exception in onSendClicked: " + e, "e");
                } finally {
                    if (br != null) {
                        try {
                            br.close();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                }

                if(hexData.length() > 0) {

                    // The hexadecimal value for the ':' (all the Arduino codes starts with this
                    // character) character is 3A
                    String hexDataProcessed = hexData.toString().replace(":", "3A");
//                    String hexDataProcessed = hexData.replace(":", "3A");

                    byte[] binaryFile = new byte[hexDataProcessed.length() / 2];
                    for(int i=0; i < hexDataProcessed.length(); i+=2) {
                        Integer iHex = Integer.parseInt(hexDataProcessed.substring(i, i + 2),16);
                        binaryFile[i/2] = iHex.byteValue();

                    }

                    outStream = mBluetoothConnection.getBTOutputStream();
                    inputStream = mBluetoothConnection.getBTInputStream();

                    boolean result = false;

                    try {
                        STK500v1 p = new STK500v1(outStream, inputStream, log, binaryFile);

                        // Upload (256 is the recommended value in the library)
                        result = p.programUsingOptiboot(false, 256);
                    } catch (Exception e) {
                        log.logcat("Exception in programming: " + e, "e");
                    }

                    final boolean wasBoardProgrammed = result;

                    log.logcat("initializeExecuteButton: Protocol code stopped", "d");
                    handler.sendEmptyMessage(0);

                    // Update the UI from the UI thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {

                            if(wasBoardProgrammed) {
                                Toast.makeText(MainActivity.this, "The board was programmed!", Toast.LENGTH_LONG).show();
                                log.makeToast("The board was programmed!");
                            } else {
                                log.makeToast("There was an error. The board wasn't programmed. " +
                                        "Try resetting the board just after pressing the send button");
                            }
                            MainActivity.this.findViewById(R.id.progress_bar).setVisibility(View.GONE);

                            stopBluetoothConnection();
                        }
                    });
                }

            }
        }).start();

    }

   public void printToConsole(String msg){}

    /************************************ PERMISSIONS **********************************************/
    @AfterPermissionGranted(RC_LOCATION_PERM)
    private void requestPermissions() {
       String[] perms = {Manifest.permission.ACCESS_COARSE_LOCATION};

       // If permission is already granted don't do anything else here
       if (EasyPermissions.hasPermissions(this, perms)) return;

       EasyPermissions.requestPermissions(this, getString(R.string.permission_location),
          RC_LOCATION_PERM, perms);
    }

   @Override public void onPermissionsGranted(int requestCode, List<String> perms) {
      log.logcat("Location permission granted", "d");
   }

   @Override
   public void onPermissionsDenied(int requestCode, List<String> perms) {
      log.logcat("Location permission denied", "d");

      // (Optional) Check whether the user denied any permissions and checked "NEVER ASK AGAIN."
      if (!EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
         new AlertDialog.Builder(this)
            .setMessage(getString(R.string.rationale_location))
            .setPositiveButton(android.R.string.ok,  null)
            .create()
            .show();
      }
   }

   @Override
   public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
      super.onRequestPermissionsResult(requestCode, permissions, grantResults);

      // Forward results to EasyPermissions
      EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
   }

}

