import { Filesystem, Directory, Encoding } from '@capacitor/filesystem';
import { Capacitor } from '@capacitor/core';
import { Toast } from '@capacitor/toast';

/**
 * Save a Blob to the Android device filesystem
 */
export async function saveBlobToAndroid(blob: Blob, filename: string): Promise<string> {
    try {
        // Convert Blob to base64
        const base64Data = await blobToBase64(blob);

        // Remove the data URL prefix (e.g., "data:application/pdf;base64,")
        const base64Content = base64Data.split(',')[1];

        // Write file to the Documents directory
        const result = await Filesystem.writeFile({
            path: `Download/${filename}`,
            data: base64Content,
            directory: Directory.ExternalStorage, // Use ExternalStorage to make it visible in Downloads
            encoding: Encoding.UTF8,
        });

        // Show a toast message (optional but helpful)
        await Toast.show({
            text: `File saved to Downloads: ${filename}`,
            duration: 'long',
        });

        return result.uri;
    } catch (error) {
        console.error('Error saving file to Android:', error);
        throw error;
    }
}

/**
 * Helper to convert Blob to Base64 string
 */
function blobToBase64(blob: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = reject;
        reader.onload = () => {
            resolve(reader.result as string);
        };
        reader.readAsDataURL(blob);
    });
}

/**
 * Check if running on Android
 */
export function isAndroid(): boolean {
    return Capacitor.getPlatform() === 'android';
}
