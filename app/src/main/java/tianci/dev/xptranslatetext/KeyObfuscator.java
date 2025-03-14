package tianci.dev.xptranslatetext;

import android.util.Base64;

/**
 * 	These API keys are collected from GitHub. Please use them with gratitude and care. Love :)
 * 	Use simple encryption to prevent easy detection by search engines; no special meaning.
 */
public class KeyObfuscator {
    private static final String[] OBFUSCATED_KEYS = {
            "QUl6YVN5RGdmT3l4RkwzWEZ6Q0dQcEpNNXNtcWRjM3ctQVFRcHR3",
            "QUl6YVN5Q0ltb1hZWUd3ZlVwWVc4NFpLU0RPQ2pUSlZpUDZSeFFv",
            "QUl6YVN5RHNPYThHLWVxRkJpZVFUYWcxdS03ZWtuQ011X291dlZF",
            "QUl6YVN5RDV4UTVtUFpZcWhWZVJRS3BuMWpvVV9pNGRIQzRmcmFz",
            "QUl6YVN5RERhX29BT2ZwMy1lYXpSN1Y2UzRMUl9nVEJLaXVlUTNr",
            "QUl6YVN5QTJFc3F0dGRQa1FRZmJReF9uMXBabC1iODN5ZUcxMUZj",
            "QUl6YVN5RERhX29BT2ZwMy1lYXpSN1Y2UzRMUl9nVEJLaXVlUTNr",
            "QUl6YVN5RDNJOC1lUlh4ZVpwMGE0NEQzTHgyaXRlTzVvY0FhNTFr",
            "QUl6YVN5QlJKT1BvZDB2WVl2cHJpSkVzMlZoLTdza21BeVE4Ni1z",
            "QUl6YVN5Q0ltb1hZWUd3ZlVwWVc4NFpLU0RPQ2pUSlZpUDZSeFFv",
            "QUl6YVN5QU91alZRMlNKdnZnd2pJS09vY0xWUUpSdV9OMHA3WW5Z",
            "QUl6YVN5QVNHQ3d0YnV6UG9iRW9zQkhUZUNuX29ROFh2OEtkNTJz",
            "QUl6YVN5REpnXzZGZENxQUdTY0dXUTNsUXNXMXJiVk9Ka0dPZW9V",
            "QUl6YVN5RG5ZU3gyS3JBMWxmZ1VQQWY4UGZZOGRzSFc4QmdhNHVN",
            "QUl6YVN5RC1mR2F2UDdJSjM1bzNNTXVfS0RqMkoxZHBMRzVqcGw4",
            "QUl6YVN5RC1LUnJ4UFV6eC1YWG1HYmdhV2g3MVVBakdLR0tKZnNN",
            "QUl6YVN5Q3lMeWlnbjdIQzJaYVJjVGFrMGVTM0ExaXMxM2d0QWJN",
            "QUl6YVN5REk4ZUpHNEhxRjYwYTZzbnZTUllGb1hucy02UVBRTjM4",
            "QUl6YVN5QXd1V0JOeFR6cW9nX2xrSHo0WC1zUk1EYkZIT3ZRWkJZ",
            "QUl6YVN5QkR3bmdjU29YX1NaYUhoU3pfc1FtVnc3YTY2ZjZrcm40",
            "QUl6YVN5REd5UGRDY2NiLTJ4YjEweXBrY1JlTXB1YV8xSl8tWmE0",
            "QUl6YVN5QzFwOHp0NlpGWUs4REQ3RlB6S2FLUkxMZk1fbHM3SWUw",
            "QUl6YVN5RERtWV9iXzZQUkFDdlFSaTUzd1J0NEdjMlE0Z2pDdGxF",
            "QUl6YVN5Q2ZEYXNxTlg3TW9KZFNQRDBKbG91QnZUVlAyWmJ1QUpz",
            "QUl6YVN5QXRncjA0U3U5ZEpWelVHaUJhdjlSU2RWM2NGWm16czBj",
    };

    public static String[] getApiKeys() {
        String[] realKeys = new String[OBFUSCATED_KEYS.length];
        for (int i = 0; i < OBFUSCATED_KEYS.length; i++) {
            realKeys[i] = decodeBase64(OBFUSCATED_KEYS[i]);
        }
        return realKeys;
    }

    private static String decodeBase64(String base64) {
        byte[] decodedBytes = Base64.decode(base64, Base64.NO_WRAP);
        return new String(decodedBytes);
    }
}
