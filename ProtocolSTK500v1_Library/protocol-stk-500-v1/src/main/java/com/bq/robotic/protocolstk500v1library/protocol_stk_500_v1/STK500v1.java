package com.bq.robotic.protocolstk500v1library.protocol_stk_500_v1;

/**
 *  Copyright 2013 UbiCollab
 *  
 *  This file is part of STK500ForJava.
 *
 *	STK500ForJava is free software: you can redistribute it and/or modify
 *	it under the terms of the GNU Lesser General Public License as published by
 *	the Free Software Foundation, either version 3 of the License, or
 *	(at your option) any later version.
 *
 *	STK500ForJava is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *	GNU Lesser General Public License for more details.
 *
 *	You should have received a copy of the GNU Lesser General Public License
 *	along with STK500ForJava.  If not, see <http://www.gnu.org/licenses/>.
 */
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.concurrent.TimeoutException;


/**
 * The protocol class for STK500v1. The current implementation only works with
 * the Optiboot bootloader.
 * Instancing this class parses the hexadecimal file to be programmed, and prepares
 * the wrapper that handles reading from Android Bluetooth sockets (required due to
 * lack of interruption or timeout exceptions in the socket and streams).
 * 
 * Programming is initiated by running one of the programUsingXXXXX() methods (which is
 * blocking, so running in another thread is recommended), and progress can be monitored
 * by using getProtocolState() and getProgress(). The progress only increments during
 * writing or reading.
 */
public class STK500v1 {
	private OutputStream output;
	private InputStream input;
	private Logger logger;
	private IReader reader;
	/**Used to prevent stack overflow**/
	private int syncStack = 0;
	private int uploadFileTries = 0;
	private volatile double progress = 0;
	private volatile ProtocolState state;
	/** Used to interact with the binary file */
	private Hex hexParser;
	/** Flag used to mark that a timeout has occurred */
	private boolean timeoutOccurred = false;
	/** Flag used to mark successful timeout recovery */
	private boolean recoverySuccessful = false;

	private boolean readWrittenPage = false;

	private ArrayList<Long> statistics;
	private boolean partialRecovery;
	private int timeoutRecoveries;
	private Thread readerThread;

	/**
	 * Initialize the programmer communicating with the Optiboot bootloader. This does
	 * not start the programming process, call the
	 * {@link #programUsingOptiboot(boolean, int) programUsingOptiboot} method for that.
	 * @param log Logger interface implementation for logging
	 * @param binary byte array in Intel hex format
	 */
	public STK500v1 (OutputStream output, InputStream input, Logger log, byte[] binary) {
		state = ProtocolState.INITIALIZING;
		this.hexParser = new Hex(binary, log);

		this.output = output;
		this.input = input;
		this.logger = log;
		logger.logcat("STKv1 constructor: Initializing protocol code", "v");

		statistics = new ArrayList<Long>();
	}

	/**
	 * Prepares the wrapper class ({@link Reader})
	 */
	private void initializeWrapper() {
		reader = new Reader(input, logger);
		readerThread = new Thread((Runnable) reader);
		readerThread.start();

		reader.start();
		while(reader.getState() != EReaderState.WAITING) {
			try {
				Thread.sleep(2);
			} catch (InterruptedException e) {}
		}
		waitForReaderStateActivated();

		logger.logcat("STKv1 constructor: ReadWrapper should be started now", "v");
		state = ProtocolState.READY;
	}

	/**
	 * Get the state the protocol is in.
	 */
	public ProtocolState getProtocolState() {
		return state;
	}

	/**
	 * Attempts to recover from a timeout by rapidly sending synchronization requests
	 * to the device, but then ignoring the actual response (apart from seeing if any
	 * response is detected at all).
	 * 
	 * If a response is detected, the receiving buffer is cleared and then a proper
	 * request for synchronization is attempted.
	 * Failure to detect any response means the device is not responding to programmer
	 * commands; a soft or hard reset is then required.
	 */
	private void recover() {
		logger.logcat("Recover: Attempting timeout recovery", "i");
		timeoutOccurred = true;
		recoverySuccessful = false;
		for (int i = 0; i < 5; i++) { 
			partialRecovery = false;
			if (spamSync()) {
				partialRecovery = true;
				while (reader.getState() != EReaderState.WAITING) {}
				waitForReaderStateActivated();
				//ignore bytes received from spamming (or even older ones)
				reader.forget();
				try {
					synchronized(this){
						wait(5);
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				if (getSynchronization()) {
					recoverySuccessful = true;
					timeoutRecoveries++;
					logger.logcat("Recover: recovery successful - recovered from " +
							timeoutRecoveries + " so far.", "i");
					break;
				}
			} else {
				logger.logcat("recover: Unable to regain comms", "i");
				restartReader();
				break;
			}
		}
	}

	private void restartReader() {
		logger.logcat("restartReader: restarting reader", "d");
		boolean stopScheduled = false;
		boolean startScheduled = false;
		while (reader.getState() != EReaderState.STOPPED) {
			if (!stopScheduled) {
				stopScheduled = reader.stop();
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

		waitForReaderStateActivated();

		while (reader.getState() != EReaderState.WAITING) {
			if (!startScheduled) {
				startScheduled = reader.start(); 
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		waitForReaderStateActivated();
	}

	/**
	 * Send synchronization requests without waiting for a response.
	 * @return
	 */
	private boolean spamSync() {
		byte[] command = {ConstantsStk500v1.STK_GET_SYNC, ConstantsStk500v1.CRC_EOP};
		logger.logcat("spamSync: sending commands", "d");
		boolean wrongStateNotified = false;
		for (int i = 0; i < 500; i++) {
			if (reader.getState() == EReaderState.TIMEOUT_OCCURRED) {
				if (!waitForReaderStateActivated(10)) {
					logger.logcat("spamSync: gave up waiting for state activation", "d");
					continue;
				}

				int result = reader.getResult();
				logger.logcat("spamSync: reader.getresult returns: " + result, "i");
				if (result == IReader.TIMEOUT_BYTE_RECEIVED) {
					logger.logcat("SpamSync: Returning true", "i");
					return true;
				}
			} else if (!wrongStateNotified) {
				wrongStateNotified = true;
				logger.logcat("spamSync: reader not in TIMEOUT_OCCURRED, but in " +
						reader.getState(), "i");
			}
			try {
				output.write(command);
			} catch (IOException e) {
				logger.logcat("Unable to send sync: " + e.getMessage(), "i");
				return false;
			}
			try {
				Thread.sleep(5);
			} catch (InterruptedException e) {}
		}
		logger.logcat("SpamSync: unable to recover. Returning false", "i");
		return false;
	}
	
	/**
	 * Set progress.
	 * @param p Progress to set. Valid input is 0 - 100.
	 */
	private void setProgress(double p) {
		if((int)p > 100) {
			logger.logcat("setProgress: Value too high, values was: " + p, "w");
			progress = 100;
		}
		else if((int)p < 0) {
			logger.logcat("setProgress: Value too low, values was: " + p, "w");
			progress = 0;
		}
		else {
			progress = p;
		}
		
	}
	
	/**
	 * Return progress of programming as integer, 0 - 100.
	 * If verification is enabled, writing goes from 0-50 and reading continues to 100.
	 * Otherwise, writing uses the entire scale.
	 * @return progress
	 */
	public int getProgress() {
		return (int)progress;
	}

	/**
	 * Print to log how much time the writing spent.
	 * Printing highest, average and lowest time spent. 
	 */
	private void writingStats() {
		long min = Long.MAX_VALUE;
		long max = 0;
		int size = statistics.size();
		long sum = 0;
		long average = 0;

		for (int i = 0; i < statistics.size(); i++) {
			long temp = statistics.get(i);
			if (temp > max) {
				max = temp;
			}
			if (temp < min) {
				min = temp;
			}
			sum += temp;
		}
		if (size != 0) average = sum/size;

		logger.logcat("writingStats: MAX: " + max, "i");
		logger.logcat("writingStats: MIN: " + min, "i");
		logger.logcat("writingStats: Average of " + size + ": " + average, "i");
	}

	/**
	 * Start the programming process. This includes initializing communication
	 * with the bootloader.
	 * 
	 * @param checkWrittenData Verify data after the write process. Recommended
	 * value is true, but to speed things up this can be skipped.
	 * @param numberOfBytes Number of bytes to write and read at once.
	 * Recommended value is 128.
	 * 
	 * @return True if the arduino was programmed. If returning false it is
	 * recommended to run this again or verify written data by using readWrittenBytes 
	 */
	public boolean programUsingOptiboot(boolean checkWrittenData, int numberOfBytes) {
		initializeWrapper();
		timeoutOccurred = false;
		partialRecovery = false;
		recoverySuccessful = false;
		timeoutRecoveries = 0;
		state = ProtocolState.CONNECTING;
		long startTime;
		long endTime;
		boolean entered;
		logger.logcat("programUsingOptiboot: Initializing programmer", "v");

		// Restart the arduino.
		// This requires the ComputerSerial library on arduino.
		if (!resetAndSync()) {
			shutdownReaderCompletely();
			return false;
		}

		// Enter programming mode
		startTime = System.currentTimeMillis();
		for (int i = 0; i < 5; i++) {
			logger.logcat("programUsingOptiboot: Attempt #" + i, "v");

			entered = enterProgramMode();
			endTime = System.currentTimeMillis();

			logger.logcat("programUsingOptiboot: enterProgramMode took: " +
					(endTime-startTime) + " ms", "v");

			if (entered) {

				// Check hex file
				if(hexParser.getChecksumStatus()) {
					logger.logcat("programUsingOptiboot: Starting to write and read.", "v");

					// Erase chip before starting to program
					if(!chipEraseUniversal()) {
						if (timeoutOccurred && !recoverySuccessful){
							state = ProtocolState.ERROR_WRITE;
							shutdownReaderCompletely();
							return false;
						} else if (timeoutOccurred) {
							timeoutOccurred = false;
						}
						logger.logcat("uploadFile: Chip not erased!", "w");
						break;
					}

					//Upload and verify uploaded bytes.
					statistics = new ArrayList<Long>();
					if (writeAndReadFile(checkWrittenData, numberOfBytes)){
						logger.logcat("programUsingOptiboot: program successful", "d");
					} else {
						//Write and collect statistics from writing
						writingStats();
						state = ProtocolState.ERROR_WRITE;
						if (timeoutOccurred && !recoverySuccessful){
							//TODO Should trigger hard reset and new attempt
							logger.logcat("ProgramUsingOptiboot: Lost communication " +
									"during programming, hard reset required!", "i");
							shutdownReaderCompletely();
							return false;
						} else if (timeoutOccurred) {
							//recovered
							timeoutOccurred = false;
						}
					}
					//Write and collect statistics from writing
					writingStats();
				}
				else {
					state = ProtocolState.ERROR_PARSE_HEX;
					logger.logcat("programUsingOptiboot: Hex file not OK! Cancelling...", "w");
					shutdownReaderCompletely();
					return false;
				}

				// Leave programming mode
				logger.logcat("programUsingOptiboot: Trying to leave programming mode...", "i");
				for (int j = 0; j < 3; j++) {
					if(leaveProgramMode()) {
						logger.logcat("programUsingOptiboot: The arduino has now " +
								"left programming mode.", "i");
						if (state != ProtocolState.ERROR_READ &&
								state != ProtocolState.ERROR_WRITE) {
							state = ProtocolState.FINISHED;
						}
						shutdownReaderCompletely();
						return true;
					}
					else {
						if (timeoutOccurred && !recoverySuccessful) {
							logger.logcat("programUsingOptiboot: Unable to recover" +
									" from timeout. May attempt soft reset and" +
									" new try before falling back to hard reset", "e");
							break;
						} else if (timeoutOccurred) {
							timeoutOccurred = false;
						}
					}
					if(j>2) {
						logger.logcat("programUsingOptiboot: Giving up on leaving " +
								"programming mode.", "i");
						if (state != ProtocolState.ERROR_READ &&
								state != ProtocolState.ERROR_WRITE) {
							state = ProtocolState.FINISHED;
						}
						shutdownReaderCompletely();
						return false;
					}
				}
			}
			//couldn't enter programming mode
			else if (timeoutOccurred && !recoverySuccessful){
				state = ProtocolState.ERROR_CONNECT;
				shutdownReaderCompletely();
				return false;
			} else if (timeoutOccurred) {
				//recovered
				timeoutOccurred = false;
			}
			//Try a soft reset before next attempt
			if (!resetAndSync()) {
				logger.logcat("ProgramUsingOptiboot: Unable to reset and sync!", "i");
				shutdownReaderCompletely();
				return false;
			}
		}

		// Could not enter programming mode!
		state = ProtocolState.ERROR_CONNECT;
		shutdownReaderCompletely();
		return false;
	}

	@SuppressWarnings("deprecation")
	private void shutdownReaderCompletely() {
		long timeout = 10000;
		long time = System.currentTimeMillis();
		boolean stopScheduled = false;
		while (reader.getState() != EReaderState.STOPPED) {
			if(System.currentTimeMillis() > time + timeout) {
				//last resort - the reader thread is unresponsive to proper termination
				readerThread.stop();
				return;
			}
			else if (!stopScheduled){
				stopScheduled = reader.stop();
			}
		}
		waitForReaderStateActivated(timeout/2);
		((Reader)reader).requestCompleteStop();
	}

	/**
	 * Try to restart arduino and get synchronized.
	 * @return True if the arduino restarted and sync is regained.
	 */
	private boolean resetAndSync() {
		boolean connect = false;
		for (int i = 0; i < 3; i++) {
			if (reader.getState() != EReaderState.WAITING) {
				restartReader();
			}
			waitForReaderStateActivated();
//			if(!softReset()) {
//				logger.logcat("programUsingOptiboot: Arduino didn't restart!", "w");
//				state = ProtocolState.ERROR_CONNECT;
//				return false;
//			}
//
//			logger.logcat("programUsingOptiboot: Waiting for the arduino to restart", "i");
//			// Wait for the arduino to start up
//			try {
//				Thread.sleep(150);
//			} catch (InterruptedException e) {
//			}

			logger.logcat("programUsingOptiboot: Attempting synchronization", "i");
			//get sync and set parameters
			connect = getSynchronization();
			if (connect) {
				return true;
			}
		}

		state = ProtocolState.ERROR_CONNECT;
		logger.logcat("programUsingOptiboot: Unable to reset and sync!", "i");
		reader.stop();
		return false;
	}

	/**
	 * Reset arduino. This requires the ComputerSerial library on the arduino.
	 * It will fail if extensive corruption occurs during programming, and will require a
	 * {@link #hardwareReset() hard reset}.
	 */
	private boolean softReset() {
		// Bytes needed to reset arduino using the ComputerSerial library
		byte[] write = new byte[6];
		write[0] = (byte)0xFF;
		write[1] = (byte)0x00;
		write[2] = (byte)0x01;
		write[3] = (byte)0xFF;
		write[4] = (byte)0x00;
		write[5] = (byte)0x00;

		logger.logcat("softReset: Sending bytes to restart arduino: " + Hex.bytesToHex(write), "d");

		// Restart arduino by sending reset command to arduino
		try {
			for (int i = 0; i < write.length; i++) {
				output.write(write[i]);
			}
		} catch (IOException e) {
			logger.logcat("softReset: Could not write to arduino.", "w");
			return false;
		}

		// We don't get any result back from arduino, so we assume it's OK
		logger.logcat("softReset: Restarting arduino...", "w");
		return true;
	}
	
	//TODO: This is not used by optiboot!
	//Note that these are hardcoded towards the Arduino Uno
	//	private boolean sendExtendedParameters() {
	//		//(byte) 45,  (byte) 05, (byte) 04, (byte) d7, (byte) c2, (byte) 00, (byte) 20
	//		byte[] command = new byte[] {
	//				(byte) 0x45,  (byte) 5, (byte) 4, (byte) 0xd7, (byte) 0xc2, (byte) 0, (byte) 0x20
	//			};
	//		try {
	//			logger.logcat("sendExtendedParameters: sending bytes: " +
	//					Hex.bytesToHex(command), "d");
	//			output.write(command);
	//		} catch (IOException e) {
	//			logger.logcat("sendExtendedParameters: error sending command", "w");
	//		}
	//		return checkInput();
	//	}

	//TODO: This is not used by optiboot!
	//Note that these are hardcoded towards the Arduino Uno
	//	private boolean sendParameters() {
	//		//B [42] .               [86] .    [00] .    [00] .    [01] .    [01] .    [01] .   [01] .     [03] .       [ff] .      [ff] .        [ff] .      [ff] .   ph[00] .     pl[80] .    [04] .    [00] .    [00] .    [00] .       [80] . [00]   [20]
	//		byte[] command = new byte[] {
	//			//(byte) 0x42, (byte) 0x86, (byte) 0, (byte) 0, (byte) 1, (byte) 1, (byte) 1, (byte) 1, (byte) 3, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0, (byte) 0x80, (byte) 4, (byte) 0, (byte) 0, (byte) 0, (byte) 0x80, (byte) 0, (byte) 0x20
	//			(byte) 0x42, (byte) 0x86, (byte) 0, (byte) 0, (byte) 1, (byte) 1, (byte) 1, (byte) 1, (byte) 3, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0, (byte) 0x80, (byte) 4, (byte) 0, (byte) 0, (byte) 0, (byte) 0x80, (byte) 0, (byte) 0x20
	//		};
	//		try {
	//			logger.logcat("sendParameters: sending bytes: " + Hex.bytesToHex(command), "d");
	//			output.write(command);
	//		} catch (IOException e) {
	//			logger.logcat("sendparameters: error sending command", "w");
	//		}
	//		return checkInput();
	//	}

	//TODO: Not used by optiboot
//	private void setParameters() {
		//		for (int i = 0; i < 10; i++) {
		//		if (sendParameters()) {
		//			logger.logcat("STK Constructor: succeeded in setting parameters", "i");
		//			break;
		//		} else if (i ==9) {
		//			//give up
		//			logger.logcat("STK Constructor: Unable to set parameters", "i");
		//			readWrapper.terminate();
		//			return;
		//		}
		//	}
		//
		//	for (int i = 0; i < 10; i++) {
		//		if (sendExtendedParameters()) {
		//			logger.logcat("STK Constructor: succeeded in setting extended parameters", "i");
		//			break;
		//		} else if (i ==9) {
		//			//give up
		//			logger.logcat("STK Constructor: Unable to set extended parameters", "i");
		//			readWrapper.terminate();
		//			return;
		//		}
		//	}
//	}

	/**
	 * Attempt to handshake with the Arduino. The method is modified to account for
	 * the Optiboot loader not returning a version string, just the
	 * in sync and OK bytes.
	 * @return -1 on failure and Arduino otherwise
	 */
	//TODO: Not used since it's not really relevant for use with Optiboot; it only
	//returns the same values as getSynchronization
	@SuppressWarnings("unused")
	private String checkIfStarterKitPresent() {
		logger.logcat("checkIfStarterKitPresent: Detect programmer", "v");
		String version = "";

		//Send request
		try {
			byte[] out = new byte[] {
					ConstantsStk500v1.STK_GET_SIGN_ON, ConstantsStk500v1.CRC_EOP
			};
			output.write(out);
			logger.logcat("checkIfStarterKitPresent: Sending bytes to get " +
					"starter kit: " + Hex.bytesToHex(out), "d");
		} catch (IOException e) {
			logger.logcat("checkIfStarterKitPresent: Communication problem: " +
					"Can't send request for programmer version", "i");
			return "-1";
		}

		//Read response
		try {
			char[] response = new char[7];
			int responseIndex = 0;
			int readResult = 0;
			byte readByte;
			while (readResult >= 0) {
				readResult = read(TimeoutValues.CONNECT);
				if (readResult == IReader.RESULT_NOT_DONE) {
					//stream from job not accepted.
					logger.logcat("checkIfStarterKitPresent: Couldn't start reading", "i");
					break;
				}
				readByte = (byte) readResult;
				logger.logcat("checkIfStarterKitPresent: Read byte: " + readByte, "v");
				if (responseIndex == 0 && readByte == ConstantsStk500v1.STK_INSYNC) {
					//Good response, next byte should be first part of the string
					//optiboot never sends the string
					responseIndex = 7;
					continue;
				} else if (responseIndex == 7 && readByte == ConstantsStk500v1.STK_OK) {
					//index too high for array writing, return string if all OK
					version = "Arduino";
					return version;
				} else if (responseIndex >= 0 && responseIndex < 7) {
					//read string
					response[responseIndex] = (char) readByte;
					responseIndex++;
				} else if (responseIndex == 0 && readByte == ConstantsStk500v1.STK_NOSYNC){
					logger.logcat("checkIfStarterKitPresent: Not in sync", "w");
					break;
				} else {
					logger.logcat("checkIfStarterKitPresent: Not terminated by STK_OK!", "v");
					break;
				}
			}
		} catch (TimeoutException e) {
			logger.logcat("checkIfStarterKitPresent: Timeout in checkIfStarterkitPresent!", "w");
			recover();
		} catch (IOException e) {
			logger.logcat("checkIfStarterKitPreset: Unable to read!", "e");
			return version;
		}

		return version;
	}

	/**
	 * Command to try to regain synchronization when sync is lost. Returns when
	 * sync is regained, or it exceeds x tries.
	 * 
	 * @return true if sync is regained, false if number of tries exceeds x
	 */
	private boolean getSynchronization() {
		byte[] getSyncCommand = {ConstantsStk500v1.STK_GET_SYNC, ConstantsStk500v1.CRC_EOP};

		try {
			output.write(getSyncCommand);
		} catch (IOException e) {
			logger.logcat("getSynchronization: Unable to write output in " +
					"getSynchronization", "i");
			e.printStackTrace();
			return false;
		}
		//If the response is valid, return. If not, continue
		if (checkInput(false, ConstantsStk500v1.STK_GET_SYNC, TimeoutValues.CONNECT)) {
			logger.logcat("getSynchronization: Sync achieved! Returning true", "v");
			syncStack = 0;
			return true;
		} else if (timeoutOccurred && partialRecovery && !recoverySuccessful) {
			//this method can't recover from timeout on its own
			logger.logcat("GetSynchronization: Only partial timeout recovery, give" +
					" up.", "i");
			return false;
		} else if (timeoutOccurred && recoverySuccessful) {
			timeoutOccurred = false;
			logger.logcat("GetSynchronization: Recovered from timeout! Returning true.", "i");
			//now in sync
			return true;
		}
		logger.logcat("getSynchronization: Could not get synchronization. Returning false.", "d");
		return false;
	}

	/**
	 * Enter programming mode. Set device and programming parameters before calling.
	 * 
	 * @return true if the connected device was able to enter programming mode.
	 * False if not.
	 */
	private boolean enterProgramMode() {
		//send command
		byte[] command = new byte[] {
				ConstantsStk500v1.STK_ENTER_PROGMODE, ConstantsStk500v1.CRC_EOP 	
		};
		logger.logcat("enterProgramMode: Sending bytes to enter programming mode: "
				+ Hex.bytesToHex(command), "d");
		try {
			output.write(command);
		} catch (IOException e) {
			logger.logcat("enterProgramMode: Communication problem on sending" +
					"request to enter programming mode", "i");
			return false;
		}

		//check response
		boolean ok = checkInput(true, ConstantsStk500v1.STK_ENTER_PROGMODE, TimeoutValues.CONNECT);
		if (!ok) {
			logger.logcat("enterProgramMode: Unable to enter programming mode", "w");
		}
		return ok;
	}

	/**
	 * Leave programming mode.
	 * 
	 * @return True if the arduino was able to leave programming mode. false if not.
	 */
	private boolean leaveProgramMode() {
		//send command
		byte[] command = new byte[] {
				ConstantsStk500v1.STK_LEAVE_PROGMODE, ConstantsStk500v1.CRC_EOP
		};

		try {
			output.write(command);
		} catch (IOException e) {
			logger.logcat("leaveProgramMode: Communication problem on leaving" +
					"programming mode", "i");
		}

		//check response
		boolean ok = checkInput();
		if (!ok) {
			logger.logcat("leaveProgramMode: Unable to leave programming mode", "w");
		}
		return ok;
	}

	/**
	 * Erase the device to prepare for programming.
	 * If using the optiboot bootloader, use {@link #chipEraseUniversal()
	 * chipEraseUniversal}
	 * @return true if successful.
	 */
	@SuppressWarnings("unused")
	private boolean chipErase() {
		//TODO: Not used by optiboot
		byte[] command = new byte[]{ConstantsStk500v1.STK_CHIP_ERASE, ConstantsStk500v1.CRC_EOP};

		logger.logcat("chipErase: Sending bytes to erase chip: " + Hex.bytesToHex(command), "d");

		try {
			output.write(command);
			logger.logcat("chipErase: Chip erased!", "d");
		} catch (IOException e) {
			logger.logcat("chipErase: Communication problem on chip erase.", "v");
			return false;
		}

		boolean ok = checkInput();
		if (!ok) {
			logger.logcat("chipErase: No sync. EOP not recieved for chip erase.", "v");
		}
		return ok;
	}

	/**
	 * Erase the device to prepare for programming using the universal command
	 * @return true if successful.
	 */
	private boolean chipEraseUniversal() {
		//TODO: Not used by optiboot
		byte[] command = new byte[6];

		command[0] = ConstantsStk500v1.STK_UNIVERSAL;
		command[1] = (byte)172; 
		command[2] = (byte)128;
		command[3] = (byte)0;
		command[4] = (byte)0;
		command[5] = ConstantsStk500v1.CRC_EOP;

		logger.logcat("chipEraseUniversal: Sending bytes to erase chip: " + Hex.bytesToHex(command), "d");

		//Try to write
		try {
			output.write(command);
		} catch (IOException e) {
			logger.logcat("chipEraseUniversal: Communication problem on chip erase.", "v");
			return false;
		}

		//read start command + n data bytes + end command
		byte[] in = new byte[3];
		int numberOfBytes;

		logger.logcat("chipEraseUniversal: Waiting for " + in.length + " bytes.", "d");

		//Read data
		try {
			for (int i = 0; i < 3; i++) {
				numberOfBytes = read(TimeoutValues.READ);

				in[i] = (byte)numberOfBytes;

				switch (i) {
				case 0:
					if(numberOfBytes != ConstantsStk500v1.STK_INSYNC) {
						logger.logcat("chipEraseUniversal: STK_INSYNC failed on first byte, " +
								Hex.oneByteToHex(in[i]), "w");
						return false;
					}
				case 1:
					continue;
				case 2:
					if(numberOfBytes == ConstantsStk500v1.STK_OK) {
						logger.logcat("readPage: STK_OK, " +
								Hex.oneByteToHex(in[i]), "w");
					}
					return true;
				default:
					return false;
				}
			}
			//Something went wrong
			logger.logcat("readPage: Something went wrong...", "w");
			return false;
		} catch (TimeoutException e) {
			logger.logcat("readPage: Unable to read", "w");
			return false;
		} catch (IOException e) {
			logger.logcat("readPage: Problem reading! " + e.getMessage(), "e");
			return false;
		}
	}

	/**
	 * Check if the write/read address is automatically incremented while using 
	 * the Cmnd_STK_PROG/READ_FLASH/EEPROM commands. Since STK500 always 
	 * auto-increments the address, this command will always be successful.
	 * 
	 * @return true if response is STK_INSYNC and STK_OK, false if not.
	 */
	@SuppressWarnings("unused")
	private boolean checkForAddressAutoincrement() {

		//TODO: Add call to this method for non-Optiboot devices

		byte[] command = new byte[2];

		command[0] = ConstantsStk500v1.STK_CHECK_AUTOINC;
		command[1] = ConstantsStk500v1.CRC_EOP;

		try {
			output.write(command);
		} catch (IOException e) {
			logger.logcat("checkForAddressAutoincrement: Unable to write output " +
					"in checkForAddressAutoincrement", "i");
			e.printStackTrace();
			return false;
		}

		return checkInput();
	}

	/**
	 * Load 16-bit address down to starterkit. This command is used to set the 
	 * address for the next read or write operation to FLASH or EEPROM. Must 
	 * always be used prior to Cmnd_STK_PROG_PAGE or Cmnd_STK_READ_PAGE.
	 * 
	 * @param address the address that is to be written as an integer
	 * 
	 * @return true if it is OK to write the address, false if not.
	 */
	private boolean loadAddress(int address) {
		//Split integer address into two bytes address 
		byte[] tempAddr = packTwoBytes(address / 2);

		byte[] loadAddr = new byte[4];

		loadAddr[0] = ConstantsStk500v1.STK_LOAD_ADDRESS;
		loadAddr[1] = tempAddr[1];
		loadAddr[2] = tempAddr[0];
		loadAddr[3] = ConstantsStk500v1.CRC_EOP;

		logger.logcat("loadAddress: Sending bytes to load address: " + 
				Hex.bytesToHex(loadAddr), "d");
		logger.logcat("loadAddress: Memory address to load: " + address +
				" (" + (address/2) + ")", "d");
		try {
			output.write(loadAddr);
		} catch (IOException e) {
			logger.logcat("loadAddress: Unable to write output in loadAddress", "w");
			e.printStackTrace();
			return false;
		}

		// Check if address was loaded
		if (checkInput()){
			logger.logcat("loadAddress: address loaded", "i");
			return true;
		}
		else {
			logger.logcat("loadAddress: failed to load address.", "w");
			return false;
		}
	}

	/**
	 * Takes an integer, splits it into bytes, and puts it in an byte array
	 * 
	 * @param integer the integer that is to be split
	 * @return an array with the integer as bytes, with the most significant first
	 */
	private byte[] packTwoBytes(int integer) {
		byte[] bytes = new byte[2];
		//store the 8 least significant bits
		bytes[1] = (byte) (integer & 0xFF);
		//store the next 8 bits
		bytes[0] = (byte) ((integer >> 8) & 0xFF);
		return bytes;
	}

	/**
	 * Method used to program one byte in EEPROM memory
	 * 
	 * @param data the byte of data that is to be programmed
	 * @return true if response is STK_INSYNC and STK_OK, false if not.
	 */
	@SuppressWarnings("unused")
	private boolean programDataMemory(byte data) {

		//TODO: Not supported by Optiboot

		byte[] programCommand = new byte[3];

		programCommand[0] = ConstantsStk500v1.STK_PROG_DATA;
		programCommand[1] = data;
		programCommand[2] = ConstantsStk500v1.CRC_EOP;

		try {
			output.write(programCommand);
		} catch (IOException e) {
			logger.logcat("programDataMemory: Could not write output in " +
					"programDataMemory", "i");
			e.printStackTrace();
			return false;
		}

		return checkInput();
	}


	/**
	 * Download a block of data to the starterkit and program it in FLASH or 
	 * EEPROM of the current device. The data block size should not be larger 
	 * than 256 bytes. bytes_high and bytes_low
	 * are part of an integer that describes the address to be written/read
	 * 
	 * @param writeFlash boolean indicating if it should be written to flash
	 * memory or EEPROM. True = flash. False = EEPROM. Writing to EEPROM is not
	 * supported by optiboot
	 * @param data byte array of data
	 * 
	 * @return true if response is STK_INSYNC and STK_OK, false if not.
	 */
	private boolean programPage(boolean writeFlash, byte[] data) {
		byte[] programPage = new byte[5+data.length];
		byte memtype;

		programPage[0] = ConstantsStk500v1.STK_PROG_PAGE;

		programPage[1] = (byte) ((data.length >> 8) & 0xFF);
		programPage[2] = (byte) (data.length & 0xFF);

		// Write flash
		if (writeFlash) {
			memtype = (byte)'F';
		}
		// Write EEPROM
		else {
			// This is not implemented in optiboot
			throw new IllegalArgumentException("Does not support writing to EEPROM.");
			
//			memtype = (byte)'E';
		}
		programPage[3] = memtype;

		//Put all the data together with the rest of the command
		for (int i = 0; i < data.length; i++) {
			programPage[i+4] = data[i];
		}

		programPage[data.length+4] = ConstantsStk500v1.CRC_EOP;

		logger.logcat("programPage: Length of data to program: " + data.length, "v");
		logger.logcat("programPage: Writing bytes: " + Hex.bytesToHex(programPage), "d");
		logger.logcat("programPage: Data array: " + Hex.bytesToHex(data), "v");
		logger.logcat("programPage: programPage array, length: " + programPage.length, "v");

		// Send bytes
		try {
			output.write(programPage);
		} catch (IOException e) {
			logger.logcat("programPage: Could not write output in programDataMemory", "i");
			e.printStackTrace();
			return false;
		}
		long currentTime = System.currentTimeMillis();
		boolean result = checkInput(false, ConstantsStk500v1.STK_PROG_PAGE, TimeoutValues.WRITE);

		if (result) statistics.add(System.currentTimeMillis() - currentTime);

		return result;
	}


	/**
	 * Read a block of data from FLASH or EEPROM of the current device. The data
	 * block size should not be larger than 256 bytes. bytes_high and bytes_low
	 * are part of an integer that describes the address to be written/read.
	 * Remember to use {@link #loadAddress(int) loadAddress} every time before you start
	 * reading. STK500v1 supports {@link #checkForAddressAutoincrement() auto increment},
	 * but we do not recommend relying on this.
	 * 
	 * @param address integer
	 * @param writeFlash boolean indicating if it should be written to flash memory
	 * or EEPROM. True = flash. False = EEPROM 
	 * 
	 * @return an byte array with the response from the selected device on the format
	 * [Resp_STK_INSYNC, data, Resp_STK_OK] or 
	 * [Resp_STK_NOSYNC] (If no Sync_CRC_EOP received). If the response does not
	 * match any of the above, something went wrong and the method returns null.
	 * The caller should then retry.
	 */
	private byte[] readPage(int address, boolean writeFlash) {
		byte[] addr = packTwoBytes(address);
		return readPage(addr[0], addr[1], writeFlash);
	}

	/**
	 * Read a block of data from FLASH or EEPROM of the current device. The data
	 * block size should not be larger than 256 bytes. bytes_high and bytes_low
	 * are part of an integer that describes the address to be written/read.
	 * Remember to use {@link #loadAddress(int) loadAddress} every time before you start
	 * reading. STK500v1 supports {@link #checkForAddressAutoincrement() auto increment},
	 * but we do not recommend relying on this.
	 * 
	 * @param bytes_high most significant byte of block size
	 * @param bytes_low least significant byte of block size
	 * @param writeFlash boolean indicating if it should read flash memory
	 * or EEPROM. True = flash. False = EEPROM 
	 * 
	 * @return an byte array with the response from the selected device on the format
	 * [Resp_STK_INSYNC, data, Resp_STK_OK] or 
	 * [Resp_STK_NOSYNC] (If no Sync_CRC_EOP received). If the response does not
	 * match any of the above, something went wrong and the method returns null.
	 * The caller should then retry.
	 */
	private byte[] readPage(byte bytes_high, byte bytes_low, boolean writeFlash) {
		byte[] readCommand = new byte[5];
		byte memtype;

		readCommand[0] = ConstantsStk500v1.STK_READ_PAGE;

		readCommand[1] = bytes_high;
		readCommand[2] = bytes_low;
		
		// Read flash
		if (writeFlash) {
			memtype = (byte)'F';
		}
		// Read EEPROM
		else {
			// This is not implemented in optiboot
			throw new IllegalArgumentException("Does not support reading from EEPROM.");
			
//			memtype = (byte)'E';
			
		}
		readCommand[3] = memtype;
		readCommand[4] = ConstantsStk500v1.CRC_EOP;

		logger.logcat("readPage: Sending bytes: " + 
				Hex.bytesToHex(readCommand), "d");

		// Send bytes
		try {
			output.write(readCommand);
		} catch (IOException e) {
			logger.logcat("readPage: Could not write output read command in " +
					"readPage", "w");
			e.printStackTrace();
		}

		int numberOfBytes = 0;

		//read start command + n data bytes + end command
		byte[] in = new byte[unPackTwoBytes(bytes_high, bytes_low)]; 

		logger.logcat("readPage: Waiting for " + in.length + " bytes.", "d");

		//Read data
		try {
			for (int i = 0; i < in.length+2; i++) {
				numberOfBytes = read(TimeoutValues.READ);

				// First byte
				if(i==0) {
					if(numberOfBytes != ConstantsStk500v1.STK_INSYNC) {
						logger.logcat("readPage: STK_INSYNC failed on first byte, " +
								Hex.oneByteToHex((byte)numberOfBytes), "w");
						return null;
					}
					else {
						logger.logcat("readPage: STK_INSYNC, " + Hex.oneByteToHex((byte)numberOfBytes), "d");
						continue;
					}
				}
				// Last byte
				else if(i==in.length+1) {
					if(numberOfBytes != ConstantsStk500v1.STK_OK) {
						logger.logcat("readPage: STK_OK failed on last byte, " + i +
								", value " + Hex.oneByteToHex((byte)numberOfBytes), "w");
						return null;
					}
					else {
						logger.logcat("readPage: Read OK.", "d");
						return in;
					}
				}
				else {
					in[i-1] = (byte)numberOfBytes;
				}
			}
			//Something went wrong
			logger.logcat("readPage: Something went wrong...", "w");
			return null;
		} catch (TimeoutException e) {
			logger.logcat("readPage: Unable to read! " + e.getMessage(), "w");
			return null;
		} catch (IOException e) {
			logger.logcat("readPage: Unable to read! " + e.getMessage(), "w");
			return null;
		}
	}


	/**
	 * Read one byte from EEPROM memory.
	 * 
	 * @return an byte array with the response from the selected device on the format
	 * [Resp_STK_INSYNC, data, Resp_STK_OK] or 
	 * [Resp_STK_NOSYNC] (If no Sync_CRC_EOP received). If the response does not
	 * match any of the above, something went wrong and the method returns null.
	 * The caller should then retry.
	 */
	@SuppressWarnings("unused")
	private byte[] readDataMemory() {

		//TODO: Not supported by Optiboot

		byte[] readCommand = new byte[2];
		byte[] in = new byte[3];

		readCommand[0] = ConstantsStk500v1.STK_READ_DATA;
		readCommand[1] = ConstantsStk500v1.CRC_EOP;

		try {
			output.write(readCommand);
		} catch (IOException e) {
			logger.logcat("readDataMemory: Could not write output read command " +
					"in readDataMemory", "i");
			e.printStackTrace();
		}

		int numberOfBytes = 0;

		try {
			numberOfBytes = input.read(in);
		} catch (IOException e) {
			logger.logcat("readDataMemory: Could not read input", "i");
			e.printStackTrace();
		}

		if (numberOfBytes == 3 && in[0] == ConstantsStk500v1.STK_INSYNC && 
				in[2] == ConstantsStk500v1.STK_OK) return in;

		else if (numberOfBytes == 1 && in[0] == ConstantsStk500v1.STK_NOSYNC) return in;

		//If the method does not return in one of the above, something went wrong
		return null;
	}

	/**
	 * Read one word from FLASH memory.
	 * 
	 * @return an byte array with the response from the selected device on the format
	 * [Resp_STK_INSYNC, flash_low, flash_high, Resp_STK_OK] or 
	 * [Resp_STK_NOSYNC] (If no Sync_CRC_EOP received). If the response does not
	 * match any of the above, something went wrong and the method returns null.
	 * The caller should then retry.
	 */
	@SuppressWarnings("unused")
	private byte[] readFlashMemory() {

		byte[] readCommand = new byte[2];
		byte[] in = new byte[4];

		readCommand[0] = ConstantsStk500v1.STK_READ_FLASH;
		readCommand[1] = ConstantsStk500v1.CRC_EOP;

		try {
			output.write(readCommand);
		} catch (IOException e) {
			logger.logcat("readFlashMemory: Could not write output read command " +
					"in readFlashMemory", "i");
			e.printStackTrace();
		}

		int numberOfBytes = 0;
		try {
			numberOfBytes = input.read(in);
		} catch (IOException e) {
			logger.logcat("readFlashMemory: Could not read input in readFlashMemory", "i");
			e.printStackTrace();
		}

		if (numberOfBytes == 4 && in[0] == ConstantsStk500v1.STK_INSYNC 
				&& in[3] == ConstantsStk500v1.STK_OK) return in;

		else if (numberOfBytes == 1 && in[0] == ConstantsStk500v1.STK_NOSYNC) return in;

		//If the method does not return in one of the above, something went wrong
		return null;
	}

	/**
	 * Check input from the Arduino.
	 * Uses {@link #checkInput(boolean, byte, TimeoutValues)
	 * checkInput(boolean checkCommand, byte command)} internally
	 * @return true if response is STK_INSYNC and STK_OK, false if not
	 */
	private boolean checkInput() {
		return checkInput(false, (byte) 0, TimeoutValues.DEFAULT);
	}

	/**
	 * Method used to get and check input from the Arduino. It reads the input, and
	 * check whether the response is STK_INSYNC and STK_OK, or STK_NOSYNC. If the
	 * response is STK_INSYNC and STK_OK the operation was successful. If not 
	 * something went wrong. <br><br>
	 * 
	 * If only STK_INSYNC and STK_OK is supposed to be returned from this method,
	 * use {@link #checkInput()}
	 * 
	 * @param checkCommand boolean used set if it is possible that this method
	 * returns something else than STK_INSYNC and STK_OK. If this is possible,
	 * set checkCommand to true.
	 * @param command byte used to identify what command is sent to the connected
	 * device. Only used if checkCommand is true.
	 * @param timeout 
	 * 
	 * @return true if response is STK_INSYNC and STK_OK, false if not.
	 */
	private boolean checkInput(boolean checkCommand, byte command, TimeoutValues timeout) {

		int intInput = -1;

		logger.logcat("checkInput called with command: " + Hex.oneByteToHex(command), "w");
		logger.logcat("checkInput: checkCommand = " + checkCommand, "i");

		try {
			intInput = read(timeout);

			if (intInput == -1) {
				logger.logcat("checkInput: End of stream encountered", "w");
				return false;
			}

			byte byteInput;

			if (intInput == ConstantsStk500v1.STK_INSYNC){
				logger.logcat("checkInput: received INSYNC", "i");

				intInput = read(timeout);
				logger.logcat("checkInput: intInput = " + intInput, "i");

				if (intInput == -1) {
					logger.logcat("checkInput: End of stream encountered", "w");
					return false;
				}

				//Input is not equal to -1. Cast to byte
				byteInput = (byte)intInput;

				//if this is a command expected to return other things in addition to sync and ok:
				if (checkCommand) {
					switch (command) {
					case ConstantsStk500v1.STK_ENTER_PROGMODE : {
						if (byteInput == ConstantsStk500v1.STK_NODEVICE) {
							logger.logcat("checkInput: Error entering programming " +
									"mode: Programmer not found", "w");
							//impossible to recover from
							throw new RuntimeException("STK_NODEVICE returned");
						} else if (byteInput == ConstantsStk500v1.STK_OK) {
							return true;
						} else {
							logger.logcat("checkInput: Reponse was STK_INSYNC but not " +
									"STK_NODEVICE or STK_OK", "i");
							return false;
						}
					}
					default : {
						throw new IllegalArgumentException("Unhandled argument:" + command);
					}
					}

				} else {
					if (byteInput == ConstantsStk500v1.STK_OK) {

						logger.logcat("checkInput: received OK. Returning true", "i");
						//Two bytes sent. Response OK. Return true
						return true;
					}
					logger.logcat("checkInput: Reponse was STK_INSYNC but not STK_OK", "v");
					return false;
				}
			}
			else {
				if(syncStack>2) {
					logger.logcat("checkInput: Avoid stack overflow, not in sync!", "v");
					return false;
				}
				logger.logcat("checkInput: Response was not STK_INSYNC, attempting " +
						"synchronization.", "w");
				syncStack++;
				return false;
			}

		} catch (TimeoutException e) {
			logger.logcat("checkInput: Timeout!", "w");
			if (!timeoutOccurred) {
				logger.logcat("checkInput: Trying to recover", "w");
				recover();
			}
			return false;

		} catch (IOException e) {
			logger.logcat("checkInput: Can't read! " + e.getMessage(), "w");
			return false;
		}
	}

	/**
	 * Method used to program one word to the flash memory.
	 * 
	 * @param flash_low first byte of the word to be programmed.
	 * @param flash_high last byte of the word to be programmed.
	 * 
	 * @return true if the method was able to program the word to the flash memory,
	 * false if not.
	 */
	@SuppressWarnings("unused")
	private boolean programFlashMemory(byte flash_low, byte flash_high) {

		byte[] uploadFile = new byte[4];

		uploadFile[0] = ConstantsStk500v1.STK_PROG_FLASH;
		uploadFile[1] = flash_low;
		uploadFile[2] = flash_high;
		uploadFile[3] = ConstantsStk500v1.CRC_EOP;

		try {
			logger.logcat("programFlashMemory: sending bytes to write word: " +
					Hex.bytesToHex(uploadFile), "d");
			output.write(uploadFile);
		} catch (IOException e) {
			logger.logcat("programFlashMemory: Unable to write output in programFlashMemory", "i");
			e.printStackTrace();
			return false;
		}

		if(checkInput()) {
			logger.logcat("programFlashMemory: word written", "v");
			return true;
		}
		else {
			logger.logcat("programFlashMemory: failed to write word", "w");
			return false;
		}
	}

	/**
	 * Reset the arduino with Hardware. Reconnect to trigger reset.
	 * This method is intended to be used to recover after both {@link #recover() timeout
	 * recovery} and {@link #softReset() soft reset} fail to reestablish communications
	 * with the device.
	 * 
	 * Not implemented due to time constraints.
	 * 
	 * This method would close the connection and then reconnect to the device; this
	 * would trigger a pin on the Bluetooth device connected to the reset pin on the
	 * device (through the required capacitors/transistors/diodes, depending on the
	 * hardware implementation).
	 * @return True if the bluetooth connection was disconnected and reconnected.
	 */
	private boolean hardwareReset() {
		logger.logcat("hardwareReset: Trying to reset arduino...", "d");

		//		try {
		//			// Disconnect
		//			input.close();
		//			output.close();
		//			
		//			// Sleep
		//			Thread.sleep(2000);
		//			
		//			// Reconnect
		//			//TODO: Add reconnect
		//		
		//		//TODO: Log if any exceptions occurs
		//		} catch (IOException e) {
		//		} catch (InterruptedException e) {
		//		}

		// Reconnect

		uploadFileTries++;

		return true;
	}

	/**
	 * Upload and read files to the flash memory. This method sends the content of
	 * the binary byte array in pairs of two to the flash memory. Can also be used
	 * to read data and compare this to the hex file.
	 * 
	 * @param checkWrittenData Verify written bytes.
	 * @param bytesToLoad How many bytes to write or read at once.
	 * 
	 * @return True if uploading and reading was successful.
	 */
	private boolean writeAndReadFile(boolean checkWrittenData, int bytesToLoad) {
		setProgress(0);
		uploadFileTries = 0;
		if(checkWrittenData) readWrittenPage = true;
		else readWrittenPage = false;

		boolean success = uploadFile(bytesToLoad, true);

		if(success && checkWrittenData) {
			if(uploadFile(bytesToLoad, false)) {
				success = true;
			}
		}

		return success;
	}


	/**
	 * Upload and read files to the flash memory. This method sends the content of
	 * the binary byte array in pairs of two to the flash memory. Can also be used
	 * to read data and compare this to the hex file.
	 * 
	 * @param bytesToLoad How many bytes to write or read at once.
	 * @param write If this method should write or read. True = write. 
	 * 
	 * @return True if everything was successful.
	 */
	private boolean uploadFile(int bytesToLoad, boolean write) {
		// Calculate progress
		state = ProtocolState.WRITING;//TODO: if Check checkReadWriteBytes and chipErase
		//universal needs state updates after merging

		logger.logcat("progress: " + getProgress() + " %", "d");

		logger.logcat("uploadFile: Data bytes to write: " +
				bytesToLoad, "d");

		// Counter used to keep the position in the hex-file
		int hexPosition = 0;

		//Run through the entire hex file, ignoring the last line
		while (hexPosition < hexParser.getDataSize()) {
			// Give up...
			if(uploadFileTries>10) return false;

			// Get bytes from hex file
			byte[] tempArray = hexParser.getHexLine(hexPosition, bytesToLoad);

			logger.logcat("uploadFile: " + hexPosition + ", " + bytesToLoad, "v");
			logger.logcat("uploadFile: " + Hex.bytesToHex(tempArray), "v");

			// No more data bytes to load, return true
			if(tempArray.length == 0) {
				return true;
			}

			//Load address, 5 attempts
			for (int j = 1; j < 5; j++) {
				if(loadAddress(hexPosition)) { 
					logger.logcat("uploadFile: loadAddress OK after " + j + " attempts.", "v");
					break;
				} else {
					// Trying to reset
					if(hardwareReset()) continue;

					if (timeoutOccurred && !recoverySuccessful){
						return false;
					}
					else if (timeoutOccurred) {
						timeoutOccurred = false;
						uploadFileTries++;
					}
				}
			}

			boolean success = true;

			if(write) {
				logger.logcat("uploadFile: Trying to write data.", "d");

				// Check if programming of page was successful.
				// Increment counter and program next page
				if (programPage(true, tempArray)) {
					hexPosition+=tempArray.length;

					// Calculate progress
					double tempProgress = (double)hexPosition / (double)hexParser.getDataSize();
					
					if(readWrittenPage) setProgress(tempProgress*50);
					else setProgress(tempProgress*100);
					
					logger.logcat("progress: " + getProgress() + " % " +
							hexPosition + " / " + hexParser.getDataSize(), "d");
				}
				else {
					success = false;
				}
			}
			else {
				logger.logcat("uploadFile: Trying to read written data.", "d");

				// Check if reading of written data was successful.
				// Increment counter and read next page
				if(readPage(bytesToLoad, false) == tempArray) {
					hexPosition+=tempArray.length;

					// Calculate progress
					logger.logcat("hexPosition: " + hexPosition +
							", hexParser.getDataSize(): " + hexParser.getDataSize(), "d");
					setProgress((double)hexPosition / (double)hexParser.getDataSize() + 50);
					
					logger.logcat("progress: " + getProgress() + " % ", "d");
				}
				else {
					success = false;
				}
			}

			//Programming was unsuccessful. Try again without incrementing
			if(!success) {
				if (timeoutOccurred && !recoverySuccessful) {
					// Trying to reset
					if(hardwareReset()) continue;

					return false;
				}
				else if (timeoutOccurred) {
					timeoutOccurred = false;
					uploadFileTries++;
					continue;
				}

				//				//FIXME: Remove this?
				//				programPageTries++;
				//				
				//				int numberOfTries = 3;
				//				for (int i = 0; i < numberOfTries; i++) {
				//					logger.logcat("uploadFile: Line: " + hexPosition + ", Retry: " + i, "w");
				//					if(loadAddress(hexPosition)) {
				//						break;
				//					}
				//					else if (timeoutOccurred && !recoverySuccessful){
				//						return false;
				//					}
				//					else if (timeoutOccurred) {
				//						timeoutOccurred = false;
				//					}
				//					else if(i == numberOfTries - 1) {
				//						logger.logcat("uploadFile: loadAddress failed!", "w");
				//						state = ProtocolState.ERROR_WRITE;
				//						
				//						// Try to reset with hardware
				//						if(hardwareReset()) {
				//							continue;
				//						}
				//						// Could not reset using hardware
				//						else {
				//							return false;
				//						}
				//					}
				//				}
			}
		}
		logger.logcat("uploadFile: End of file. "+
				"Upload finished with success.", "d");

		return true;
	}


	/**
	 * Read two unsigned bytes into an integer
	 * @param high Most significant byte
	 * @param low  Least significant byte
	 * @return
	 */
	private static int unPackTwoBytes(byte high, byte low) {
		int out = (decodeByte(high) << 8) | (decodeByte(low));
		return out;
	}

	/**
	 * Get the unsigned value of a byte
	 * @param unsignedByte
	 * @return
	 */
	private static int decodeByte(byte unsignedByte) {
		return 0xFF & unsignedByte;
	}

	/**
	 * Reads a single byte, will be interrupted after a while
	 * Uses {@link #read(byte[], TimeoutValues)} internally.
	 * @param timeout The selected timeout enumeration chosen. Used to determine
	 * timeout length.
	 * @return -1 if end of stream encountered, otherwise 0-255
	 * @throws java.util.concurrent.TimeoutException
	 * @throws java.io.IOException
	 */
	private int read(TimeoutValues timeout) throws TimeoutException, IOException {
		return read(null, timeout);
	}

	/**
	 * Will attempt to fill the entire buffer, if unable to fill it the number of read
	 * bytes will be returned. If no buffer is sent (null) this method will read
	 * a single byte.
	 * 
	 * This method makes use of the {@link IReader#read(TimeoutValues)} method to
	 * perform the actual reading.
	 * 
	 * @param buffer Array of bytes to store the read bytes
	 * @param timeout The selected timeout enumeration chosen. Used to determine
	 * timeout length.
	 * @return -1 if end of stream encountered, otherwise the number of bytes read
	 * for a non null buffer, or the value of the single byte.
	 * @throws java.util.concurrent.TimeoutException
	 * @throws java.io.IOException
	 */
	private int read(byte[] buffer, TimeoutValues timeout) throws TimeoutException,
	IOException {
		long wait = 50;
		long time = System.currentTimeMillis();
		logger.logcat("read: waiting for reader waiting state", "i");
		while (reader.getState() != EReaderState.WAITING){
			if (System.currentTimeMillis() - time > wait) {
				logger.logcat("read: Giving up waiting for reader", "d");
				return IReader.RESULT_NOT_DONE;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		waitForReaderStateActivated(10);
		return reader.read(timeout);
	}

	/**
	 * Waits for the current state of the reader to initialize completely
	 * @param timeout How long to wait, pass 0 to wait indefinitely
	 * @return true if the state activated, false if waiting was aborted
	 */
	private boolean waitForReaderStateActivated (long timeout) {
		logger.logcat("waitForReaderStateActivated: waiting for state to activate", "d");
		long time = System.currentTimeMillis();
		while(!reader.wasCurrentStateActivated()) {
			if (timeout > 0 && System.currentTimeMillis() - time > timeout) {
				return false;
			}
			try {
				Thread.sleep(1);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		return true;
	}

	/**
	 * Waits indefinitely for the current state to initialize.
	 * @return
	 */
	private boolean waitForReaderStateActivated() {
		return waitForReaderStateActivated(-1);
	}

	/**States for the service to check. Can also improve flow control in protocol**/
	public enum ProtocolState {
		/**The programmer is parsing the hex file and starting the read wrapper**/
		INITIALIZING,
		/**The programmer is ready to start**/
		READY,
		/**
		 * The programmer is connecting and synchronizing with the device.
		 * This includes getting/setting parameters and other checks.
		 */
		CONNECTING,
		/**
		 * The programmer is writing to the device. Progress can be checked using
		 * getProgress()
		 */
		WRITING,
		/**
		 * The programmer is reading from the device. Progress can be checked using
		 * getProgress();
		 */
		READING,
		/**The programmer has finished executing.**/
		FINISHED,
		/**Fatal error occured parsing the program**/
		ERROR_PARSE_HEX,
		/**Communications could not be properly established with the device**/
		ERROR_CONNECT,
		/**An error occured while programming the device**/
		ERROR_WRITE,
		/**An error occured while verifying the written data**/
		ERROR_READ
	}
}
