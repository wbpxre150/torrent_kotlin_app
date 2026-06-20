-keep class com.google.zxing.qrcode.** { *; }
-keep class com.google.zxing.common.** { *; }
-keep class com.google.zxing.BarcodeFormat { *; }

# Apache FtpServer + MINA + slf4j (Stage 06 FTP server)
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-keep class org.slf4j.** { *; }
-dontwarn org.apache.ftpserver.**
-dontwarn org.apache.mina.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**