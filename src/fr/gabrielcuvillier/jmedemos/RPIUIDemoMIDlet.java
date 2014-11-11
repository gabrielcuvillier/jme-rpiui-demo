/**
 * RPIUIDemoMIDlet.java
 * This file is part of RPIUIDemo
 * Copyright 2014 Gabriel Cuvillier
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
*/

package fr.gabrielcuvillier.jmedemos;

import com.oracle.httpclient.HttpClient;
import com.oracle.httpclient.HttpClientBuilder;
import com.oracle.httpclient.HttpClientException;
import com.oracle.httpclient.HttpMethod;
import com.oracle.httpclient.HttpRequest;
import com.oracle.httpclient.HttpRequestBuilder;
import com.oracle.httpclient.HttpResponse;
import com.oracle.httpclient.HttpResponseListener;
import com.oracle.json.Json;
import com.oracle.json.JsonObject;
import com.oracle.json.JsonReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.Formatter;
import java.util.Timer;
import java.util.TimerTask;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.microedition.io.AccessPoint;
import javax.microedition.io.ConnectionOption;
import javax.microedition.io.Connector;
import javax.microedition.io.ServerSocketConnection;
import javax.microedition.io.SocketConnection;
import javax.microedition.midlet.MIDlet;

/**
 * RPIUIDemo MIDlet. <p>
 * 
 * This demonstration program shows how the Raspberry Pi and the RPIUI expansion 
 * board may be used to create a simple embedded and connected object. <p>
 * 
 * At its core, this program drives the low level RPIUI board from BitWizard. This 
 * board allow access through I2C bus to a 16x2 LCD output, 6 input buttons, analog 
 * input with ADC, 2 temperature sensors, and RTC Clock.  <p>
 * 
 * Description may be found at:  <br>
 * http://www.bitwizard.nl/shop/raspberry-pi/raspberry-pi-ui-16x2  <br>
 * See RPIUIDevice class for more detailed informations on its driver implementation.  <p>
 * 
 * Using this device, the application provide several simple functionalities to illustrate 
 * some specific parts of Java ME or RPIUI board. Each functionality is accessible 
 * using one of the device buttons:  <br>
 * - 1: (leftmost button): displaying misc informations on the LCD ("Hello World", 
 * IP address, Date and Time) <br>
 * - 2: Reading Temperature data from device and displaying it on LCD<br>
 * - 3: Do an asynchronous web query and displaying its result on LCD when request 
 * completed (currently this is querying for the average Bitcoin price 
 * in EUR)<br>
 * - 6: simulate application reset (cleanup all resources correctly and restart)<p>
 * 
 * Additionally, the application may also be controlled remotely from another device. 
 * This is the purpose of the companion program "RemoteRPIUIControllerDemo" allowing
 * to control the MIDLet externally from the Emulator device (using input Buttons 
 * of "External Events Generator"). <p>
 * 
 * Special attention have been taken to handle concurrency and synchronization nicely, 
 * as well as resource cleanup. <p>
 * 
 * See class source code for description of internal implementation. <p>
 * 
 * @author Gabriel Cuvillier
 */
public class RPIUIDemoMIDlet extends MIDlet {

    // Flag controlling application initialization
    private boolean _AppIsInit;

    // Instance to the RPIUIDevice
    private RPIUIDevice _UIdev;

    // Connection data to allow remote control of the program
    private ServerSocketConnection _ServerSocket;
    private SocketConnection _RemoteConnection;
    private InputStream _RemoteStream;
    private int _ListeningPort;
    private static final int DEFAULT_LISTENING_PORT = 19054;
    /** MIDlet attribute name to define the server listening port */
    private static final String PORT_ATTRIBUTE = "ListeningPort";
    
    // UI Event Timer
    private Timer _UIEventTimer;
    // Remote Event Thread
    private Thread _RemoteEventThread;

    // Asynchronous HTTP request flag
    private boolean _AsyncHTTPRequestIsActive;

    // Internal Display State data
    private int _CurrentDisplayState;
    private int _NextDisplayState;

    // Constructor
    public RPIUIDemoMIDlet() {
        System.out.println("I2CExample App Created");
    }

    // Start of application
    // This method is Thread Safe
    @Override
    public void startApp() {
        System.out.println("I2CExample App Started");

        synchronized (this) {
            if (!_AppIsInit) {
                // Initialize RPIUI device              
                _UIdev = new RPIUIDevice();               

                // UI Events timer (periodically checks for RPIUI pressed buttons)
                _UIEventTimer = new Timer();
                _UIEventTimer.scheduleAtFixedRate(
                        new TimerTask() {
                            @Override
                            public void run() {
                                int pressedButton = getPressedButton();

                                if (pressedButton > 0) {
                                    System.out.format("Button %d pressed on RPIUI\n", pressedButton);
                                    doAction(pressedButton);
                                } else {
                                    // Nothing pressed
                                }
                            }
                        }, 0, 100);

                try {
                    _ListeningPort = Integer.parseInt(getAppProperty(PORT_ATTRIBUTE));
                } catch (NumberFormatException nfe) {
                    _ListeningPort = DEFAULT_LISTENING_PORT;
                    Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.WARNING, "Using default listening port", "");
                }

                // Server socket to listen for remote events
                try {
                    _ServerSocket = (ServerSocketConnection) Connector.open("socket://:" + _ListeningPort);
                } catch (IOException ex) {
                    Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.WARNING, "Unable to initialize Server Socket", "");
                    // But we may continue
                }

                if (_ServerSocket != null) {
                    // Create the Remote Event Thread
                    _RemoteEventThread = new Thread(new RemoteEventRunnableImpl(this, _ServerSocket));
                    _RemoteEventThread.start();
                } else {
                    Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.WARNING, "No Remote Event Thread started", "");
                    // No server socket
                }

                _CurrentDisplayState = 0;
                _NextDisplayState = 0;

                _AppIsInit = true;

                doAction(1);
            } else {
                // App is already initialized. Don't know if it is possible or not.
                Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.WARNING, "Application already initialized", "");

            }
        }
    }

    // End of application
    // This method is Thread Safe
    @Override
    public void destroyApp(boolean unconditional) {

        System.out.println("IC2Example Destroyed");

        synchronized (this) {
            if (_AppIsInit == true) {

                _AppIsInit = false;

                if (_ServerSocket != null) {
                    // Close server socket connection
                    try {
                        _ServerSocket.close();
                        _ServerSocket = null;
                    } catch (IOException ex) {
                        Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.SEVERE, null, ex);
                    }

                    try {
                        if (_RemoteStream != null) {
                            _RemoteStream.close();
                            _RemoteStream = null;
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.SEVERE, null, ex);
                    }

                    try {
                        if (_RemoteConnection != null) {
                            _RemoteConnection.close();
                            _RemoteConnection = null;
                        }
                    } catch (IOException ex) {
                        Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.SEVERE, null, ex);
                    }
                    
                    _RemoteEventThread = null;
                }

                // Cancel Timer 
                // Note due to the way timer work, it is possible a last task will be run once
                if (_UIEventTimer != null) {
                    _UIEventTimer.cancel();
                    _UIEventTimer = null;
                }

                // This will cancel Async HTTP Task
                _AsyncHTTPRequestIsActive = false;

                // finally, close the UI
                if (_UIdev != null) {
                    _UIdev.end();
                    _UIdev = null;
                }

                _CurrentDisplayState = 0;
                _NextDisplayState = 0;
            } else {
                //Application is not init
            }
        }
    }

    // This method is Thread Safe
    public synchronized void registerRemoteConnection(SocketConnection connection, InputStream inputStream) {
        if (_AppIsInit != false) {
            _RemoteConnection = connection;
            _RemoteStream = inputStream;
        } else {
            System.out.println("registerRemoteConnection: application have been stopped. Method skipped.");
        }
    }

    // This method is Thread Safe
    public synchronized int getPressedButton() {
        if (_AppIsInit != false) {
            return _UIdev.getPushedButton();
        } else {
            System.out.println("getPressedButton: application have been stopped. Method skipped.");
            return 0;
        }
    }

    // This method is Thread Safe
    public synchronized void doAction(int nButtonId) {
        if (_AppIsInit != false) {
            System.out.format("Doing action for button %d\n", nButtonId);
            _CurrentDisplayState = _NextDisplayState;

            if (nButtonId == 1) {
                _UIdev.clearDisplay();

                if (_CurrentDisplayState == 0) {
                    System.out.println("Display Hello World on LCD");
                    _UIdev.displayText(1, "  Hello World!");
                    _NextDisplayState = 1;
                } else if (_CurrentDisplayState == 1) {
                    System.out.println("Display IP on LCD");
                    _UIdev.displayText(1, "IP: ");
                    AccessPoint[] apList = AccessPoint.getAccessPoints(true);
                    String IPstr = "<not connected>";
                    if (apList != null && apList.length >= 2) {
                        IPstr = apList[0].getProperty("ipaddr");
                        if ("127.0.0.1".equals(IPstr)) {
                            IPstr = apList[1].getProperty("ipaddr");
                        } else {
                            // ok, keep the IP
                        }
                    } else {
                        // There is no valid connections
                    }

                    _UIdev.displayText(2, IPstr);

                    _NextDisplayState = 2;
                } else if (_CurrentDisplayState == 2) {
                    System.out.println("Display Date on LCD");
                    Date CurrentDate = new Date();
                    _UIdev.displayText(1, CurrentDate.toString());

                    _NextDisplayState = 0;
                } else {
                    // Unknown display state
                }

                // In all case, prevent the async HTTP Request started by Button 3 to proceed
                _AsyncHTTPRequestIsActive = false;
            } else if (nButtonId == 2) {
                System.out.println("Display Temperatures on LCD");
                double dInt = _UIdev.getTemperature(0);
                double dExt = _UIdev.getTemperature(1);

                Formatter InternalTempFormat = new Formatter();
                InternalTempFormat.format("%.1f", dInt);
                Formatter ExternalTempFormat = new Formatter();
                ExternalTempFormat.format("%.1f", dExt);

                _UIdev.clearDisplay();
                _UIdev.displayText(1, "Int Temp: " + InternalTempFormat.out().toString() + " C");
                _UIdev.displayText(2, "Ext Temp: " + ExternalTempFormat.out().toString() + " C");

                _NextDisplayState = 0;

                // In all case, prevent the async HTTP Request started by Button 3 to proceed
                _AsyncHTTPRequestIsActive = false;
            } else if (nButtonId == 3) {
                // If the async HTTP Request have already been started, just skip
                if (_AsyncHTTPRequestIsActive) {
                    System.out.println("HTTP Request still pending");
                } else {
                    System.out.println("Start HTTP request");
                    _UIdev.clearDisplay();

                    HttpClientBuilder clientBuilder = HttpClientBuilder.getInstance();
                    ConnectionOption<Integer> TimeoutOption = new ConnectionOption<>("Timeout", 2000);
                    clientBuilder.addConnectionOptions(TimeoutOption);
                    HttpClient client = clientBuilder.build();

                    HttpRequestBuilder requestBuilder
                            = client.build("http://api.bitcoincharts.com/v1/weighted_prices.json");
                    requestBuilder.setMethod(HttpMethod.GET);

                    HttpRequest clientRequest = requestBuilder.build();
                    _AsyncHTTPRequestIsActive = true;
                    clientRequest.invoke(new HttpResponseListener() {
                                @Override
                                public void handle(HttpResponse response) {
                                    synchronized (RPIUIDemoMIDlet.this) {
                                        if (_AsyncHTTPRequestIsActive) {
                                            System.out.println("HTTP Request done. Display OK on LCD");
                                            // handle the response
                                            _UIdev.displayText(1, "BTC/EUR: ");

                                            try (JsonReader resultBodyReader = Json.createReader(response.getBodyStream())) {
                                                JsonObject resultObject;
                                                resultObject = resultBodyReader.readObject();
                                                JsonObject currencyObject = resultObject.getJsonObject("EUR");
                                                String s = currencyObject.getString("24h");

                                                _UIdev.displayText(2, s);
                                            }

                                            _AsyncHTTPRequestIsActive = false;
                                        } else {
                                            System.out.println("HTTP Request have been canceled");
                                        }
                                    }
                                }

                                @Override
                                public void failed(HttpClientException exc) {
                                    synchronized (RPIUIDemoMIDlet.this) {
                                        if (_AsyncHTTPRequestIsActive) {
                                            System.out.println("HTTP Request done. Display Error on LCD");
                                            // handle the exception
                                            _UIdev.displayText(1, "HTTP Request Error");

                                            _AsyncHTTPRequestIsActive = false;
                                        } else {
                                            System.out.println("HTTP Request have been canceled");
                                        }
                                    }
                                }
                            });

                    _NextDisplayState = 0;
                }
            } else if (nButtonId == 6) {
                System.out.println("Restarting application");
                destroyApp(true);
                Thread RestartThread = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Thread.sleep(5000);// Wait 5 seconds before restart

                        } catch (InterruptedException ex) {
                            Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.SEVERE, null, ex);
                        }
                        startApp();
                    }
                });
                RestartThread.start();
            } else {
                // Button not implemented
            }
        } else {
            System.out.println("doAction: application have been stopped. Method skipped.");
        }
    }

    // Remote Event handling Implementation
    private static class RemoteEventRunnableImpl implements Runnable {

        private final RPIUIDemoMIDlet _App;
        private final ServerSocketConnection _ServerSocket;
        
        public RemoteEventRunnableImpl(RPIUIDemoMIDlet mainApp, ServerSocketConnection serverSocket) {
            _App = mainApp;
            _ServerSocket = serverSocket;
        }

        @Override
        public void run() {
            System.out.println("Remote Event Thread started");
            try {
                while (true) {
                    // Wait for a connection.
                    System.out.println("Waiting for connection on port " + _ServerSocket.getLocalPort());
                    try (SocketConnection remoteConnection = (SocketConnection) _ServerSocket.acceptAndOpen()) {  // Blocking call
                        // Opening stream
                        try (InputStream remoteStream = remoteConnection.openInputStream()) {
                            System.out.println("Connection accepted and Stream opened");

                            _App.registerRemoteConnection(remoteConnection, remoteStream);

                            // Read Loop
                            int c = 0;
                            while (c != -1) {
                                c = remoteStream.read(); // Blocking call

                                if (c > 0) {
                                    System.out.format("Button %d pressed from Remote\n", c);
                                    _App.doAction(c);
                                } else {
                                    // nothing pressed
                                }
                            }
                        } catch (IOException ex) {
                            System.out.println("IO Error while reading from Remote Stream");
                        }
                    }

                    System.out.println("Remote Connection and Stream closed");
                }
            } catch (IOException ex) {
                System.out.println("Server Socket closed");
                Logger.getLogger(RPIUIDemoMIDlet.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}
