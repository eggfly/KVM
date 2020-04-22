package kvm.core;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class DecryptFile {
    private static final String TAG = "DecryptFile";

    public static File main(File encFile, Context context) {
        String name = "decrypted.dex";
        try {
            File aesFile = encFile;
            Log.d("AESFILELENGTH", "aes length: " + aesFile.length());
            File aesFileBis = new File(context.getFilesDir(), name);

            FileInputStream fis;
            FileOutputStream fos;
            CipherInputStream cis;

            //Creation of Secret key
            String key = "my_secret_key";
            int length = key.length();
            if (length > 16) {
                key = key.substring(0, 15);
            }
            if (length < 16) {
                for (int i = 0; i < 16 - length; i++) {
                    key = key + "0";
                }
            }
            SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(), "AES");
            //Creation of Cipher objects
            Cipher decrypt = Cipher.getInstance("AES/CBC/PKCS5Padding");

            byte[] aByte = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0};
            IvParameterSpec ivSpec = new IvParameterSpec(aByte);
            decrypt.init(Cipher.DECRYPT_MODE, secretKey, ivSpec);

            // Open the Encrypted file
            fis = new FileInputStream(aesFile);
            cis = new CipherInputStream(fis, decrypt);
            // Write to the Decrypted file
            fos = new FileOutputStream(aesFileBis);
            try {
                byte[] mByte = new byte[8];
                int i = cis.read(mByte);
                // Log.i("MBYTE", "mbyte i: " + i);
                while (i != -1) {
                    fos.write(mByte, 0, i);
                    i = cis.read(mByte);
                }
            } catch (IOException e) {
                Log.w(TAG, e);
            }
            fos.flush();
            fos.close();
            cis.close();
            fis.close();
            return aesFileBis;
        } catch (Exception e) {
            Log.w(TAG, e);
        }
        return null;
    }
}