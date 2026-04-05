import axios from 'axios';
import { Platform } from 'react-native';
import ImageResizer from 'react-native-image-resizer';
import { BackendSchema, ScanResult } from '../types';

// Android Emulator uses 10.0.2.2 for localhost, iOS uses 127.0.0.1
export const API_BASE_URL = 'https://omr-scanner-jsc8.onrender.com';
// export const API_BASE_URL = Platform.OS === 'android' ? 'http://10.0.2.2:8000' : 'http://127.0.0.1:8000';

const api = axios.create({
  baseURL: API_BASE_URL,
});

export const fetchSchema = async (questionCount: number = 20): Promise<BackendSchema> => {
  try {
    const response = await api.get<BackendSchema>(`/schema?question_count=${questionCount}`);
    return response.data;
  } catch (error) {
    console.error('Error fetching schema:', error);
    throw error;
  }
};

export const processForm = async (
  imageUri: string,
  questionCount: number = 20,
  signal?: AbortSignal,
): Promise<ScanResult> => {
  try {
    // Görseli yüklemeden önce boyutlandır: en fazla 1600×2133, %82 kalite
    const resized = await ImageResizer.createResizedImage(
      imageUri,
      1600,
      2133,
      'JPEG',
      65,
      0,
    );

    const formData = new FormData();
    const filename = resized.name || 'photo.jpg';

    formData.append('file', {
      uri: Platform.OS === 'ios' ? resized.uri.replace('file://', '') : resized.uri,
      name: filename,
      type: 'image/jpeg',
    } as any);
    formData.append('question_count', questionCount.toString());

    const response = await api.post<ScanResult>('/process', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      signal,
    });

    return response.data;
  } catch (error: any) {
    if (error.response) {
      return { status: 'error', error: error.response.data.error || 'Server error', answers: {}, student_info: { name: '', student_number: '' }, metadata: {} };
    }
    throw error;
  }
};
