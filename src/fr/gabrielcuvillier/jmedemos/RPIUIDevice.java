/**
 * RPIUIDevice.java
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

import jdk.dio.DeviceManager;
import jdk.dio.i2cbus.I2CDevice;
import jdk.dio.i2cbus.I2CDeviceConfig;
import java.nio.ByteBuffer;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * RPIUIDevice driver implementation. <p>
 * 
 * This class is a driver for the "RPi_UI" board from BitWizard, using the I2C bus. 
 * It have been tested on the 16x2 LCD version. <p> 
 * 
 * Description and documentation for this board may be found at: <br>
 * http://www.bitwizard.nl/shop/raspberry-pi/raspberry-pi-ui-16x2 <br>
 * http://www.bitwizard.nl/wiki/index.php/User_Interface <p>
 * 
 * The driver allows to display text on the LCD, query for the last pushed button, 
 * and read temperature data from sensors connected to ADC channels.<p> 
 * 
 * For convenience, this class do not throw any checked IOException. When an IO Error 
 * occurs, it is assumed to be fatal and not recoverable, and so a RuntimeException 
 * is thrown instead.<p> 
 * 
 * Note that this class is NOT thread safe, it is responsibility of caller application 
 * to correctly handle concurrency access and issues.<p> 
 * 
 * I2C communication may be not safe too, as combined messages are not used (no support 
 * for transactions). However, in the context of this demonstration program, this 
 * should be ok.
 * 
 * See class source code for description of internal implementation. <p>
 * 
 * @author Gabriel Cuvillier
 */
public class RPIUIDevice {
    
    // I2C Device configuration and instance
    private final I2CDeviceConfig _DeviceConfig;
    private final I2CDevice       _Device;
    
    // I2C Device identification constants
    private static final byte RPIUI_I2C_CHANNEL = 1;
    private static final byte RPIUI_I2C_ADDRESS = 0x4A;
    
    // I2C Device commands
    private static final byte CMD_SET_TEXT_CURSOR      = 0x11;
    private static final byte CMD_DISPLAY_TEXT         = 0x00;
    private static final byte CMD_CLEAR_DISPLAY        = 0x10;
    private static final byte CMD_GET_PUSHED_BUTTONS   = 0x30;
    private static final byte CMD_GET_PUSHED_BUTTONS_UNIQUE   = 0x31;  // Don't used as it is buggy
    private static final byte CMD_READ_ADC_CHANNEL_0   = 0x68;
    private static final byte CMD_READ_ADC_CHANNEL_1   = 0x69;
    private static final byte CMD_REINIT_RPIUI         = 0x14;
    private static final byte CMD_SET_CONTRAST         = 0x12;
    private static final byte CMD_SET_ADC_CHANNEL_0    = 0x70;
    private static final byte CMD_SET_ADC_CHANNEL_1    = 0x71;
    private static final byte CMD_SET_ADC_NUM_CHANNELS = (byte)0x80;
    private static final byte CMD_SET_ADC_NUM_SAMPLES  = (byte)0x81;
    private static final byte CMD_SET_ADC_SHIFT        = (byte)0x82;
    
    // ADC base configuration
    private static final int    ADC_SHIFT = 6;
    private static final int    ADC_NUM_SAMPLES = 64; // 2^SHIFT
    private static final double ADC_REFERENCE_VOLTAGE = 1.1;
    private static final int    ADC_RESOLUTION = 10;
    private static final int    ADC_MAXVALUE = 1023; // 2^RESOLUTION - 1;
    
    // Temperature sensor constants
    private static final double TEMPERATURE_VOLTAGE_PER_DEGREE = 0.01;
    private static final int    TEMPERATURE_OFFSET = 50;
    private static final byte   INTERNAL_TEMPERATURE_SENSOR_1V1 = (byte)0xC7;
    private static final byte   EXTERNAL_TEMPERATURE_SENSOR_1V1 = (byte)0xC6;

    // Misc hardcoded constants 
    private static final int    MIN_DELAY_FOR_DEVICE_REINIT = 600;
    private static final int    DEFAULT_LCD_CONSTRAST = 0x50;

    // Array of 6 booleans, representing the buttons "pushed" state since last 
    // query to the device. The array is indexed with (ButtonNumber - 1).
    //
    // An effective "push" of a button is detected when its corresponding 
    // state value in the array goes from "false" to "true".
    // See getPushedButton() and computeButtonStatus() methods.
    private boolean[] _LastButtonStates = { false, false, false, false, false, false };
    
    /** Construct a RPIUIDevice instance. */
    public RPIUIDevice() {        
        try {
            // Create configuration using I2C device identification (channel and 
            // address)
            _DeviceConfig = new I2CDeviceConfig.Builder()
                .setControllerNumber(RPIUI_I2C_CHANNEL)
                .setAddress(RPIUI_I2C_ADDRESS, I2CDeviceConfig.ADDR_SIZE_7)
                .build();
            // Open the device
            _Device = DeviceManager.open(_DeviceConfig);

            // Reinitialization
            byte[] ReinitCmd = { CMD_REINIT_RPIUI, 0x01 }; // Reinit LCD
            _Device.write(ByteBuffer.wrap(ReinitCmd));
            // Required minimum wait by RPIUI after a reinit 
            Thread.sleep(MIN_DELAY_FOR_DEVICE_REINIT); 
            
            // As reinit do not cleanup device last pushed buttons states, 
            // manually call getPushedButtons once to synchronize local
            // state to device state (and prevent unwanted button press the 
            // first time the method is used by a client)
            getPushedButton();

            // Set default LCD contrast
            byte[] ContrastCmd = { CMD_SET_CONTRAST, DEFAULT_LCD_CONSTRAST };
            _Device.write(ByteBuffer.wrap(ContrastCmd));

            // ADC initialization with temperature sensors
            byte[] ConfigureADCChannel0Cmd = {CMD_SET_ADC_CHANNEL_0, 
                                              INTERNAL_TEMPERATURE_SENSOR_1V1 };
            _Device.write(ByteBuffer.wrap(ConfigureADCChannel0Cmd));
            byte[] ConfigureADCChannel1Cmd = {CMD_SET_ADC_CHANNEL_1, 
                                              EXTERNAL_TEMPERATURE_SENSOR_1V1 };
            _Device.write(ByteBuffer.wrap(ConfigureADCChannel1Cmd));
            // Set number of channels to read
            byte[] SetChannelsToSampleCmd = { CMD_SET_ADC_NUM_CHANNELS, 2 };
            _Device.write(ByteBuffer.wrap(SetChannelsToSampleCmd));
            // Set number of samples
            // Note: setting samples seem to not work correctly: it is always 
            // fixed at 64. 
            // Note 2: we add an additional byte as the register wants a "short" 
            // value (16 bits). As setting samples is not working (see previous note), 
            // this needs to be verified.
            byte[] SamplesCmd = { CMD_SET_ADC_NUM_SAMPLES, 
                                  ADC_NUM_SAMPLES, 0x00 };
            _Device.write(ByteBuffer.wrap(SamplesCmd));
            // Set Shift
            byte[] ShiftCmd = { CMD_SET_ADC_SHIFT, ADC_SHIFT };
            _Device.write(ByteBuffer.wrap(ShiftCmd));            

        }
        catch (InterruptedException | IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("IO error while initializing device of RPIUI");           
        }
    }
    
    /** End the RPIUIDevice.
     * Must be called whenever the RPIUI is no more needed */
    public final void end() {
        
        // Don't throw exceptions in this method, since we really need to close
        // the device in all possible situations
        
        try {
            // Reinit RPIUI (to cleanup everything)
            byte[] ReinitCmd = { CMD_REINIT_RPIUI, 0x01 }; 
            _Device.write(ByteBuffer.wrap(ReinitCmd));
            Thread.sleep(MIN_DELAY_FOR_DEVICE_REINIT);
        }
        catch (InterruptedException | IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);    
        } 
        finally {
            try {
                // Close the device
                _Device.close();
            } catch (IOException ex) {
                Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
    
    /** Clear LCD display */
    public final void clearDisplay() {
        
        try {
            // Setup clear display command
            byte[] ClearCmd = { CMD_CLEAR_DISPLAY, 0x01 };
            _Device.write(ByteBuffer.wrap(ClearCmd));
        }
        catch (IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("IO error while clearing display of RPIUI");
        }
    }
    
    /** Display a String at a specific line. Only ASCII characters are valid. **/
    public final void displayText(int nLine, String Text) {
        
        try {
            // Setup text cursor at line "nLine"
            byte[] LineCmd = { CMD_SET_TEXT_CURSOR,
                               // The top 3 bits are for line number, 
                               // and the bottom 5 bits are for character 
                               // position 
                               (byte) ((nLine - 1) << 5) };
            _Device.write(ByteBuffer.wrap(LineCmd));

            // Setup text write command 
            // The command size is the number of chars of the String + 1 for the 
            // command itself
            byte[] WriteCmd = new byte[Text.length() + 1];
            WriteCmd[0] = CMD_DISPLAY_TEXT;

            // Convert the String to a byte array, putting it in the command
            // Note: only ASCII characters are valid (8-bits)
            char[] chars = Text.toCharArray();
            for (int i = 0; i < chars.length; i++) {
                WriteCmd[i+1] = (byte) chars[i];
            }
            _Device.write(ByteBuffer.wrap(WriteCmd));
        }
        catch (IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("IO error while displaying Text to RPIUI");
        }
    }
    
    /** Get which button have been pushed since last call of this method.
     * The method return the pushed button number (1 to 6), or 0 if nothing have been 
     * pushed.
     * 
     * As there is no way to have an interruption to be notified that a button have been 
     * pushed, this method is intended to be called at regular time intervals. 100ms 
     * is sufficient for most usages.
     * 
     * Note that if a button is kept pushed during two consecutive calls of this method, 
     * it will count as only one "Push" (ie. the first call the method will return button 
     * number, and the next call the method will return 0). */
    public final int getPushedButton() {
        
        try {
            // Get all pushed buttons since last query to device.
            _Device.write(CMD_GET_PUSHED_BUTTONS);
            int DeviceButtonPushedStates = _Device.read();
            
            // Implementation note: 
            // The command CMD_GET_PUSHED_BUTTONS_UNIQUE is not used (0x31 -
            // which count a button kept pushed as only one push) because it 
            // is too buggy in practice. 
            //
            // So, its behavior is simulated its by software, using the regular 
            // CMD_GET_PUSHED_BUTTONS command (0x30 - which count a button kept
            // pushed as multiple pushes) and storing button states locally to 
            // be able to detect effective state changes between two consecutive 
            // calls.
            //
            // This processing is done by computeButtonStatus() method.
            
            // Compute all button status
            boolean b1 = computeButtonStatus(DeviceButtonPushedStates, 1);
            boolean b2 = computeButtonStatus(DeviceButtonPushedStates, 2);
            boolean b3 = computeButtonStatus(DeviceButtonPushedStates, 3);
            boolean b4 = computeButtonStatus(DeviceButtonPushedStates, 4);
            boolean b5 = computeButtonStatus(DeviceButtonPushedStates, 5);
            boolean b6 = computeButtonStatus(DeviceButtonPushedStates, 6);

            // Return only one pushed button, with lower button numbers priority
            if (b1)
                return 1;
            else if (b2)
                return 2;
            else if (b3)
                return 3;
            else if (b4)
                return 4;
            else if (b5)
                return 5;
            else if (b6)
                return 6;
            else 
                return 0;
        }
        catch (IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("IO error while getting button status of RPIUI");     
        }
    }
    
    /** Internal method detect and keep track if a button have been pushed or 
     * not according to device button state and driver state (using 
     * _LastButtonStates boolean array)
     * 
     *  Each first 6 bits of device state value correspond to a button state. 
     *  The leftmost nth-bit is set to 1 when nth-button have been "Pushed" 
     *  and 0 otherwise.
     * 
     *  An effective push is detected when an entry of the boolean array goes 
     *  from "false" to "true" (but not from "true" to "true", in this case it
     *  means a button is simply kept pushed). 
     */
    private boolean computeButtonStatus(int nDeviceButtonState, int nButton) {
        
        // Extract button state from device state value        
        // We use the button number as a bitmask
        int nBitMask = 1 << (6 - nButton);
        boolean buttonPressedOnDevice = ((nDeviceButtonState & nBitMask) == nBitMask);
        
        // if device says the button have been pushed
        if (buttonPressedOnDevice) {
            // check if it was already considered "pushed" in the driver state
            if (_LastButtonStates[nButton - 1] == true) { 
                // If so, it is simply still being pushed, so don't count 
                // this as a new push. Return "Not pushed", while still keeping
                // its "Pushed" state.
                return false;
            }
            else {
                // Otherwise, if it was not pushed previously before, this is 
                // indeed a new "push". 
                // Remember the "Pushed" state in the driver, and return 
                // "Pushed"
                _LastButtonStates[nButton - 1] = true;
                return true;
            }
        }
        else {
            // Otherwise, if the device says the button is not pushed,
            // Remember the "Not Pushed" state in the driver (note it was maybe 
            // "Pushed" before), and return "Not Pushed" too.
            _LastButtonStates[nButton - 1] = false; 
            return false;
        }
    }
    
    /** Get temperature data from ADC Channel
     * @param nChannel */
    public final double getTemperature(int nChannel) {
        
        try {
            // Select which channel to use:
            // 0 for internal temperature sensor
            // 1 for external temperature sensor
            byte ChannelCmdToUse;
            if (nChannel == 0) {
                ChannelCmdToUse = CMD_READ_ADC_CHANNEL_0;
            } 
            else {
                ChannelCmdToUse = CMD_READ_ADC_CHANNEL_1;
            }
            _Device.write(ChannelCmdToUse);
            
            // Read the channel value (multibyte)
            byte[] ResultBuf = { 0x00, 0x00 };
            _Device.read(ByteBuffer.wrap(ResultBuf));

            // Convert the multibyte value it into an int
            int ResultVal = (int)(ResultBuf[1] & 0xFF) << 8 | (int)(ResultBuf[0] & 0xFF);
            
            // And compute the final temperature value
            return ((ResultVal * ADC_REFERENCE_VOLTAGE / TEMPERATURE_VOLTAGE_PER_DEGREE) / ADC_MAXVALUE) 
                        - TEMPERATURE_OFFSET;
            
            // This is based on documentation and examples from:
            // http://www.bitwizard.nl/wiki/index.php/Temperature_sensor_example
            // http://www.bitwizard.nl/wiki/index.php/DIO_protocol#Using_the_analog_inputs
        }
        catch (IOException ex) {
            Logger.getLogger(RPIUIDevice.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException("IO error while getting temperature of RPIUI");     
        }
    }
}
