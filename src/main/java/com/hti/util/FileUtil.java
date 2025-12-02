package com.hti.util;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class FileUtil {
	private static Logger logger = LoggerFactory.getLogger(FileUtil.class);

	public static Properties readProperties(String filename) throws FileNotFoundException, IOException {
		Properties props = new Properties();
		FileInputStream fileInputStream = null;
		try {
			fileInputStream = new FileInputStream(filename);
			props.load(fileInputStream);
		} finally {
			if (fileInputStream != null) {
				try {
					fileInputStream.close();
				} catch (IOException ioe) {
					fileInputStream = null;
				}
			}
		}
		return props;
	}
	

	public static String readFlag(String path) {
		String flagValue = "100";
		try {
			flagValue = readValue(path, "FLAG");
		} catch (FileNotFoundException fnfex) {
		} catch (IOException ioe) {
		}
		return flagValue;
	}
	
	public static void setDefaultFlag(String path) {
		setContent(path, "FLAG = 100");
	}

	private static String readValue(String path, String param) throws FileNotFoundException, IOException {
		String value = null;
		BufferedReader reader = null;
		try {
			reader = new BufferedReader(new FileReader(path));
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.contains(param)) {
					value = line.substring(line.indexOf("=") + 1, line.length()).trim();
					break;
				}
			}
		} finally {
			if (reader != null) {
				try {
					reader.close();
				} catch (IOException ioe) {
					reader = null;
				}
			}
		}
		return value;
	}
	
	public static boolean setContent(String path, String content) {
		FileOutputStream fileOutputStream = null;
		boolean done = false;
		try {
			fileOutputStream = new FileOutputStream(path);
			fileOutputStream.write(content.getBytes());
			done = true;
		} catch (IOException ex) {
			logger.info("setContent()" + path + " : " + ex);
		} finally {
			if (fileOutputStream != null) {
				try {
					fileOutputStream.close();
				} catch (IOException ioe) {
					fileOutputStream = null;
				}
			}
		}
		return done;
	}

	public static String readContent(String file) {
		try {
			Path filePath = Path.of(file); // Specify the file path
			return Files.readString(filePath);
		} catch (Exception e) {
			logger.info(file + " Error: " + e.getMessage());
		}
		return null;
	}

}
