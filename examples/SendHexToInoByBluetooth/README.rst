=======================
SendHexToInoByBluetooth
=======================

SendHexToInoByBluetooth is a simple Android app that uses the protocol-stk-500-v1 library to program a microcontroller board such as the bq ZUM or Arduino Uno by a Bluetooth connection. It also uses the droid2ino library for the Bluetooth pairing and comunication, 

It allows you to:

* Program the microcontroller board using a Bluetooth connection. 

* Search in your mobile device for a hex file of the compiled program with which you want to program the microcontroller board. There is ``led.hex file`` in the ``arduino_hex_example`` folder that you can put on your mobile device if you want to test the app. It will make blink a led connected to the pin 13.
  
If you have any questions you can contact us through the `DIY forum <http://diy.bq.com/forums/forum/forum/>`_  or sending an email to diy@bq.com.


Installation
============

#. Install `Android Studio <https://developer.android.com/sdk/installing/studio.html>`_ and `Gradle <http://www.gradle.org/downloads>`_.

#. If you use a 64 bits Linux, you will need to install ia32-libs-multiarch::

    sudo apt-get update
    sudo apt-get upgrade
    sudo apt-get install ia32-libs-multiarch 

#. SendHexToInoByBluetooth depends on the droid2ino library. Clone the repository::

    git clone https://github.com/bq/droid2ino.git

#. Install the droid2ino library in your local repository::
  
    cd droid2ino/droid2ino
    gradle install

#. SendHexToInoByBluetooth depends on the protocol-stk-500-v1 library. Clone the repository::

    git clone https://github.com/bq/protocol-stk-500-v1.git

#. Install the ProtocolSTK500v1_Library in your local repository::
  
    cd protocol-stk-500-v1/ProtocolSTK500v1_Library
    gradle install

#. In Android Studio go to ``File`` > ``Open`` and select the ``SendHexToInoByBluetooth`` from the ``Example`` folder of the cloned repository.


Requirements
============

- `Java JDK <http://www.oracle.com/technetwork/es/java/javase/downloads/jdk7-downloads-1880260.html>`_ 

- `Android Studio <https://developer.android.com/sdk/installing/studio.html>`_ 

- `Maven <http://maven.apache.org/download.cgi>`_. If you use Ubuntu::
    
    sudo apt-get update
    sudo apt-get install maven

- `Gradle <http://www.gradle.org/downloads>`_ version 3.3


Limitations
===========

The bootloader of the microcontroller board must be listening to the Serial port (where the Bluetooth module is connected) till a timeout. The bootloader of the microcontroller board must be listening to the Bluetooth module till a timeout has passed. This time must be big enought to let the mobile device acknowledge that it is connected with the Bluetooth of the microcontroller board and start to send the program. Usually, the default bootloaders have this timeout too much short.


License
=======

SendHexToInoByBluetooth is distributed in terms of GPL license. See http://www.gnu.org/licenses/ for more details.
