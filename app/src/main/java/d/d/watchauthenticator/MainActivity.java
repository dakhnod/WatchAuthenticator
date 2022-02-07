package d.d.watchauthenticator;

import androidx.appcompat.app.AppCompatActivity;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.ParcelUuid;
import android.text.method.ScrollingMovementMethod;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.fossil.crypto.EllipticCurveKeyPair$CppProxy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActiviry";
    SharedPreferences prefs;
    EditText textUriAuthentication, textUriHandshake, textUriDevicesList, textUriSecretKeys, textAccountEmail, textAccountPassword;
    ListView scanResultList;

    String accessToken;

    String handshakeEndpoint;
    String serialNumber;

    boolean scanStarted = false;

    ArrayList<String> scanResults = new ArrayList<>();

    ArrayAdapter<String> resultAdapter;

    BluetoothAdapter adapter;
    BluetoothLeScanner scanner;
    BluetoothGattCharacteristic authCharacteristic;

    EllipticCurveKeyPair$CppProxy keyPair = EllipticCurveKeyPair$CppProxy.create();
    byte[] sharedSecret = new byte[16];
    byte[] randomNumbers = new byte[8];

    boolean authenticatedViaServer = false;

    UUID deviceInfoServiceUuid = UUID.fromString("0000180A-0000-1000-8000-00805f9b34fb");
    UUID serialNumberUuid = UUID.fromString("00002A25-0000-1000-8000-00805f9b34fb");
    UUID authServiceUuid = UUID.fromString("3dda0001-957f-7d4a-34a6-74696673696d");
    UUID authCharacteristicUuid = UUID.fromString("3dda0005-957f-7d4a-34a6-74696673696d");

    Timer requestTimeout = new Timer();

    TextView logTextView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getPreferences(MODE_PRIVATE);

        initBt();

        initViews();

        autoLogin();

        EllipticCurveKeyPair$CppProxy result = EllipticCurveKeyPair$CppProxy.create();

        Log.d("", "");
    }

    private void screenLog(String data){
        logTextView.append(data + "\n");
    }

    private void initBt() {
        BluetoothManager manager = (BluetoothManager) getSystemService(BLUETOOTH_SERVICE);
        adapter = manager.getAdapter();
        scanner = adapter.getBluetoothLeScanner();
    }

    private void autoLogin() {
        String authUril = prefs.getString("uri_authentication", null);
        if (authUril == null || authUril.isEmpty()) return;
        String refreshToken = prefs.getString("refresh_token", null);
        if (refreshToken == null || refreshToken.isEmpty()) return;

        new Thread(() -> {
            try {
                this.performAuth(authUril + "token/refresh", refreshToken);
                toast("auth successfull");
                screenLog("login success");
                runOnUiThread(() -> toggleScan(true));
            } catch (IOException | JSONException | RuntimeException | InterruptedException e) {
                e.printStackTrace();
                toast("cannot auto login");
                screenLog("login fail");
                prefs.edit().remove("refresh_token").apply();
            }
        }).start();
    }

    private void performAuth(String authUrl, String accountEmail, String accountPassword) throws JSONException, IOException, InterruptedException {
        Log.d(TAG, String.format("performAuth: %s %s %s", authUrl, accountEmail, accountPassword));
        performAuth(authUrl, new JSONObject()
                .put("clientId", "unknown")
                .put("email", accountEmail)
                .put("password", accountPassword)
        );
    }

    private void performAuth(String authUrl, String refreshToken) throws JSONException, IOException, InterruptedException {
        performAuth(authUrl, new JSONObject()
                .put("refreshToken", refreshToken)
        );
    }

    private void performAuth(String endpoint, JSONObject authObject) throws IOException, JSONException, InterruptedException {
        JSONObject resultObject = this.sendPostRequest(endpoint, authObject);
        runOnUiThread(() -> {
            try {
                handleAuthSuccess(resultObject.getString("accessToken"), resultObject.getString("refreshToken"));
            } catch (JSONException e) {
                e.printStackTrace();
            }
        });
    }

    private JSONObject sendPostRequest(String endpoint, JSONObject postData) throws IOException, JSONException, InterruptedException {
        byte[] authData = postData.toString().getBytes();

        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Content-Type", "application/json");
        if (this.accessToken != null && !this.accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.accessToken);
        }
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setFixedLengthStreamingMode(authData.length);
        connection.getOutputStream().write(authData);
        int response = connection.getResponseCode();
        if (response != 200) {
            throw new RuntimeException("response code is not 200: " + response);
        }
        InputStream is = connection.getInputStream();

        byte[] data = new byte[2048];

        int readResult = is.read(data);
        String result = new String(data);
        return new JSONObject(result);
    }

    private JSONObject sendGetRequest(String endpoint) throws IOException, JSONException, InterruptedException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        if (this.accessToken != null && !this.accessToken.isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + this.accessToken);
        }
        connection.setDoInput(true);
        int response = connection.getResponseCode();
        if (response != 200) {
            throw new RuntimeException("response code is not 200: " + response);
        }
        InputStream is = connection.getInputStream();

        byte[] data = new byte[2048];

        int readResult = is.read(data);
        String result = new String(data);
        return new JSONObject(result);
    }

    private void stopScan() {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        scanner.stopScan(scanCallback);
    }

    private void startScan() {
        BluetoothLeScanner scanner = adapter.getBluetoothLeScanner();
        scanResults.clear();
        resultAdapter.notifyDataSetChanged();
        List<ScanFilter> filers = Arrays.asList(
                new ScanFilter.Builder()
                        .setServiceUuid(ParcelUuid.fromString("3dda0001-957f-7d4a-34a6-74696673696d"))
                        .build()
        );
        scanner.startScan(
                filers,
                new ScanSettings.Builder()
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_FIRST_MATCH)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .build(),
                scanCallback
        );
    }

    ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            Log.d("Main", "found device " + result.toString());
            scanResults.add(result.getDevice().getAddress());
            runOnUiThread(() -> resultAdapter.notifyDataSetChanged());
        }

        @Override
        public void onScanFailed(int errorCode) {
            super.onScanFailed(errorCode);
            toast("LE scan failed");
        }
    };

    private void startAuthentication(BluetoothGatt gatt, String serialNumber){
        try {
            authenticatedViaServer = false;
            JSONObject requestData = new JSONObject()
                    .put("serialNumber", serialNumber);
            JSONObject response = sendPostRequest(handshakeEndpoint + "generate-pairing-key", requestData);
            byte[] randomNumber = Base64.decode(response.getString("randomKey"), 0);

            ByteBuffer dataBuffer = ByteBuffer.allocate(11);
            dataBuffer.order(ByteOrder.LITTLE_ENDIAN);

            dataBuffer.put(new byte[]{0x02, 0x01, 0x00});
            dataBuffer.put(randomNumber);

            screenLog("sending random to watch...");
            authCharacteristic.setValue(dataBuffer.array());
            boolean success = gatt.writeCharacteristic(authCharacteristic);
            if (!success) {
                requestTimeout.cancel();
                requestTimeout = null;
                screenLog("failed writing random number to watch");
                throw new RuntimeException("failed writing random number to watch");
            }
        } catch (JSONException | InterruptedException | IOException e) {
            e.printStackTrace();
            toast("failed getting random number from server");
            screenLog("failed getting random number from server");
            requestTimeout.cancel();
            requestTimeout = null;
        }
    }

    BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicRead(gatt, characteristic, status);
            if (characteristic.getUuid().equals(serialNumberUuid)) {
                serialNumber = characteristic.getStringValue(0);
                // toast("serial: " + serialNumber);
                screenLog("read serial number " + serialNumber + ". Requesting random from server...");
                startAuthentication(gatt, serialNumber);
                // gatt.disconnect();
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicChanged(gatt, characteristic);
            if (characteristic.getUuid().equals(authCharacteristicUuid)) {
                ByteBuffer buffer = ByteBuffer.wrap(characteristic.getValue());
                buffer.order(ByteOrder.LITTLE_ENDIAN);

                if(requestTimeout != null) {
                    requestTimeout.cancel();
                }
                requestTimeout = new Timer();
                requestTimeout.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        screenLog("request timeout, restarting...");
                        startAuthentication(gatt, serialNumber);
                    }
                }, 5000);

                if (buffer.get(1) == 0x01) {
                    byte[] encryptedNumbers = new byte[16];
                    buffer.position(4);
                    buffer.get(encryptedNumbers);
                    if(buffer.get(3) == 0x00) { // server challenge
                        try {
                            JSONObject responseData = sendPostRequest(
                                    handshakeEndpoint + "swap-pairing-keys",
                                    new JSONObject()
                                            .put("serialNumber", serialNumber)
                                            .put("encryptedData", Base64.encodeToString(encryptedNumbers, Base64.NO_WRAP))
                            );

                            screenLog("exchanging data with server...");
                            byte[] responseEncryptedNumbers = Base64.decode(responseData.getString("encryptedData"), 0);
                            ByteBuffer responseBuffer = ByteBuffer.allocate(19);
                            responseBuffer.order(ByteOrder.LITTLE_ENDIAN);
                            responseBuffer.put(new byte[]{0x02, 0x02, 0x00});
                            responseBuffer.put(responseEncryptedNumbers);

                            screenLog("sending server challenge response to watch...");
                            authCharacteristic.setValue(responseBuffer.array());
                            gatt.writeCharacteristic(authCharacteristic);
                        } catch (IOException | JSONException | InterruptedException e) {
                            e.printStackTrace();
                            toast("failed sending encrypted numbers to server");
                            requestTimeout.cancel();
                            requestTimeout = null;
                        }
                    }else{ // local challenge
                        try{
                            byte[] encrypted = new byte[16];

                            buffer.position(4);
                            buffer.get(encrypted);

                            SecretKeySpec keySpec = new SecretKeySpec(sharedSecret, "AES");
                            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
                            cipher.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
                            byte[] decrypted = cipher.doFinal(encrypted);

                            screenLog("re-encrypting challenge from watch...");

                            byte[] swapped = new byte[16];
                            System.arraycopy(decrypted, 8, swapped, 0, 8);
                            System.arraycopy(decrypted, 0, swapped, 8, 8);

                            cipher = Cipher.getInstance("AES/CBC/NoPadding");
                            cipher.init(Cipher.ENCRYPT_MODE, keySpec, new IvParameterSpec(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}));
                            byte[] encryptedSwapped = cipher.doFinal(swapped);

                            authCharacteristic.setValue(
                                    ByteBuffer
                                            .allocate(19)
                                            .put(new byte[]{0x02, 0x02, 0x01})
                                            .put(encryptedSwapped)
                                            .array()
                            );
                            gatt.writeCharacteristic(authCharacteristic);
                        }catch (Exception e){
                            e.printStackTrace();
                            toast("error with encryption");
                        }
                    }
                } else if (buffer.get(1) == 0x02) {
                    if (buffer.get(2) != 0x00) {
                        toast(authenticatedViaServer ? "local random number challenge failed" : "server random number challenge failed");
                        screenLog(authenticatedViaServer ? "local random number challenge failed" : "server random number challenge failed");
                        requestTimeout.cancel();
                        requestTimeout = null;
                        gatt.disconnect();
                        return;
                    }
                    screenLog("watch challenge success.");
                    if (authenticatedViaServer) {
                        String secretHex = "0x" + bytesToHex(MainActivity.this.sharedSecret);

                        screenLog(String.format("got key: %s", secretHex));

                        ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        clipboardManager.setPrimaryClip(ClipData.newPlainText("fossil key", secretHex));

                        toast("secret copied to clipboard");
                        screenLog("secret copied to clipboard.");

                        requestTimeout.cancel();
                        requestTimeout = null;
                        gatt.disconnect();
                    } else {
                        byte[] publicKey = MainActivity.this.keyPair.getPublicKey();

                        ByteBuffer publicKeyBuffer = ByteBuffer.allocate(34);
                        publicKeyBuffer.put(new byte[]{0x02, 0x03});
                        publicKeyBuffer.put(publicKey);

                        screenLog("sending public key to watch...");

                        authCharacteristic.setValue(publicKeyBuffer.array());
                        gatt.writeCharacteristic(authCharacteristic);
                        authenticatedViaServer = true;
                    }
                } else if (buffer.get(1) == 0x03) {
                    byte[] watchPublicKey = new byte[32];
                    buffer.position(3);
                    buffer.get(watchPublicKey);

                    screenLog("revived public key from watch, calculating shared secret...");

                    byte[] sharedSecret = MainActivity.this.keyPair.calculateSecretKey(watchPublicKey);
                    System.arraycopy(sharedSecret, 0, MainActivity.this.sharedSecret, 0, 16);

                    screenLog("sending local challenge random number...");

                    new Random().nextBytes(MainActivity.this.randomNumbers);
                    authCharacteristic.setValue(
                            ByteBuffer
                                .allocate(11)
                                .put(new byte[]{0x02, 0x01, 0x01})
                                .put(randomNumbers)
                                .array()
                    );
                    Log.d("bt", "written random number");
                    gatt.writeCharacteristic(authCharacteristic);
                }

            }
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
            super.onDescriptorWrite(gatt, descriptor, status);
            Log.d("bt", "descriptor written");
            screenLog("subscribed, reading serial...");
            BluetoothGattService infoService = gatt.getService(deviceInfoServiceUuid);
            BluetoothGattCharacteristic serialCharacteristic = infoService.getCharacteristic(serialNumberUuid);
            gatt.readCharacteristic(serialCharacteristic);

        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);
            Log.d("bt", "characteristic written");
            // toast("characteristic written");
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            super.onConnectionStateChange(gatt, status, newState);
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                // toast("device connected");
                screenLog("connected to device, discovering services...");
                Log.d("bt", "device connected");
                gatt.discoverServices();
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            super.onMtuChanged(gatt, mtu, status);
            screenLog("MTU changed, enabling notification...");

            BluetoothGattService authService = gatt.getService(authServiceUuid);
            authCharacteristic = authService.getCharacteristic(authCharacteristicUuid);
            gatt.setCharacteristicNotification(authCharacteristic, true);
            BluetoothGattDescriptor notification = authCharacteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"));
            notification.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
            gatt.writeDescriptor(notification);
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            super.onServicesDiscovered(gatt, status);
            screenLog("discovered services, requesting MTU...");
            gatt.requestMtu(512);
        }
    };

    private void connectDevice(String address) {
        screenLog("connecting to device " + address);
        authenticatedViaServer  = false;
        BluetoothDevice device = adapter.getRemoteDevice(address);
        BluetoothGatt gatt = device.connectGatt(this, false, gattCallback);
    }

    private void handleAuthSuccess(String accessToken, String refreshToken) {
        Log.d("", "auth success");
        this.accessToken = accessToken;
        prefs.edit().putString("refresh_token", refreshToken).apply();
        findViewById(R.id.button_scan).setVisibility(View.VISIBLE);
        findViewById(R.id.button_fetch_key).setVisibility(View.VISIBLE);
    }

    private void handleAuthenticate(View v) {
        String authenticationUri = textUriAuthentication.getText().toString();
        handshakeEndpoint = textUriHandshake.getText().toString();
        String accountEmail = textAccountEmail.getText().toString();
        String accountPassword = textAccountPassword.getText().toString();

        prefs.edit()
                .putString("uri_authentication", authenticationUri)
                .putString("uri_handshake", handshakeEndpoint)
                .putString("account_email", accountEmail)
                .putString("account_password", accountPassword)
                .apply();

        new Thread(() -> {
            try {
                this.performAuth(authenticationUri + "login", accountEmail, accountPassword);
                toast("auth successfull");
            } catch (IOException | JSONException | RuntimeException | InterruptedException e) {
                e.printStackTrace();
                toast("auth error: " + e.getMessage());
            }
        }).start();
    }

    private void toast(String data) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, data, Toast.LENGTH_SHORT).show());
    }

    @Override
    protected void onPause() {
        super.onPause();
        Log.d("Main", "finishing");
        finish();
        System.exit(0);
    }

    private void toggleScan() {
        toggleScan(!scanStarted);
    }

    private void toggleScan(boolean start) {
        if (start == scanStarted) return;
        if (scanStarted) {
            stopScan();
            ((Button) findViewById(R.id.button_scan)).setText("start scan");
        } else {
            startScan();
            ((Button) findViewById(R.id.button_scan)).setText("stop scan");
        }
        scanStarted = start;
    }

    private void handleFetchKeys(View view){
        String deviceListUri = textUriDevicesList.getText().toString();
        String secretKeysUri = textUriSecretKeys.getText().toString();

        prefs.edit()
                .putString("uri_device_list", deviceListUri)
                .putString("uri_secret_keys", secretKeysUri)
                .apply();

        if(deviceListUri.isEmpty()){
            toast("Please enter URI for device list");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject result = sendGetRequest(deviceListUri);
                    JSONArray devices = result.getJSONArray("_items");
                    screenLog("got device list");
                    if(devices.length() < 1){
                        toast("no devices configured with account");
                        screenLog("no devices found");
                        return;
                    }
                    JSONObject firstDevice = devices.getJSONObject(0);
                    String serial = firstDevice.getString("id");
                    screenLog(String.format("device serial: %s", serial));

                    String secretKeysUriFilled = String.format(secretKeysUri, serial);
                    JSONObject secretKeyResult = sendGetRequest(secretKeysUriFilled);
                    String secretKey = secretKeyResult.getString("secretKey");
                    byte[] fullKey = Base64.decode(secretKey, Base64.DEFAULT);
                    System.arraycopy(fullKey, 0, MainActivity.this.sharedSecret, 0, 16);
                    Log.d(TAG, "run: " + secretKey);

                    String secretHex = "0x" + bytesToHex(MainActivity.this.sharedSecret);

                    screenLog(String.format("got key: %s", secretHex));

                    ClipboardManager clipboardManager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    clipboardManager.setPrimaryClip(ClipData.newPlainText("fossil key", secretHex));

                    toast("secret copied to clipboard");
                    screenLog("secret copied to clipboard.");
                } catch (IOException | JSONException | InterruptedException e) {
                    e.printStackTrace();
                    toast("Error sending GET request: " + e.getMessage());
                }
            }
        }).start();
    }

    private void initViews() {
        textUriAuthentication = findViewById(R.id.uri_authentication);
        textUriHandshake = findViewById(R.id.uri_handshake);
        textAccountEmail = findViewById(R.id.account_email);
        textAccountPassword = findViewById(R.id.account_password);
        textUriDevicesList = findViewById(R.id.uri_devices_list);
        textUriSecretKeys = findViewById(R.id.uri_secret_keys);
        scanResultList = findViewById(R.id.scan_results);
        logTextView = findViewById(R.id.log_text);
        logTextView.setMovementMethod(new ScrollingMovementMethod());



        resultAdapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, scanResults);
        scanResultList.setAdapter(resultAdapter);

        scanResultList.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                toggleScan(false);
                logTextView.setText("");
                new Thread(() -> connectDevice(scanResults.get(position))).start();
            }
        });

        handshakeEndpoint = prefs.getString("uri_handshake", "");

        textUriAuthentication.setText(prefs.getString("uri_authentication", ""));
        textUriDevicesList.setText(prefs.getString("uri_device_list", ""));
        textUriSecretKeys.setText(prefs.getString("uri_secret_keys", ""));
        textUriAuthentication.setText(prefs.getString("uri_authentication", ""));
        textUriHandshake.setText(handshakeEndpoint);
        textAccountEmail.setText(prefs.getString("account_email", ""));
        textAccountPassword.setText(prefs.getString("account_password", ""));

        findViewById(R.id.button_fetch_key).setOnClickListener(this::handleFetchKeys);
        findViewById(R.id.button_authenticate).setOnClickListener(this::handleAuthenticate);
        findViewById(R.id.button_scan).setOnClickListener((v) -> {
            toggleScan();
        });
    }

    private String bytesToHex(byte[] bytes) {
        final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();
        String hex = "";
        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hex += HEX_ARRAY[v >>> 4];
            hex += HEX_ARRAY[v & 0x0F];
        }
        return hex;
    }
}