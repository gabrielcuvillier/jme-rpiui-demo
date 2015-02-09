jme-rpiui-demo
==============
RPIUIDemo MIDlet.

This demonstration program shows how the Raspberry Pi and the RPIUI expansion board may be used to create a simple embedded and connected object.

At its core, this program drives the low level RPIUI board from BitWizard. This board allow access through I2C bus to a 16x2 LCD output, 6 input buttons, analog input with ADC, 2 temperature sensors, and RTC Clock.

Description may be found at http://www.bitwizard.nl/shop/raspberry-pi/raspberry-pi-ui-16x2
See RPIUIDevice class for more detailed informations on its driver implementation.

Using this device, the application provide several simple functionalities to illustrate some specific parts of Java ME or RPIUI board. Each functionality is accessible sing one of the device buttons:
- 1: (leftmost button): displaying misc informations on the LCD ("Hello World", IP address, Date and Time)
- 2: Reading Temperature data from device and displaying it on LCD
- 3: Do an asynchronous web query and displaying its result on LCD when request completed (currently this is querying for the average Bitcoin price in EUR
- 6: simulate application reset (cleanup all resources correctly and restart, without relying on MIDLet "hard-kill" to do this)

Additionally, the application may also be controlled remotely from another device. This is the purpose of the companion program "RemoteRPIUIControllerDemo" (jme-remoterpiui-demo repository), allowing to control the MIDLet externally from the Emulator device (using input Buttons of "External Events Generator").

Special attention have been taken to handle concurrency and synchronization nicely, as well as resource cleanup. The "reset" functionality is implemented specifically to allow testing possible concurrency issues.
