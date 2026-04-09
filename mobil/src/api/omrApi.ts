import axios from 'axios';
import ImageResizer from 'react-native-image-resizer';
import ReactNativeBlobUtil from 'react-native-blob-util';
import { BackendSchema, ScanResult } from '../types';

type RawScanResult = ScanResult & {
  form_image_base64?: string;
};

// Android Emulator uses 10.0.2.2 for localhost, iOS uses 127.0.0.1
export const API_BASE_URL = 'https://omr-scanner-jsc8.onrender.com';
// export const API_BASE_URL = Platform.OS === 'android' ? 'http://10.0.2.2:8000' : 'http://127.0.0.1:8000';

const api = axios.create({
  baseURL: API_BASE_URL,
});

export const RESULT_IMAGES_DIR = `${ReactNativeBlobUtil.fs.dirs.DocumentDir}/omr_results`;

const normalizeLocalPath = (path: string) => {
  if (!path) return '';
  return path.startsWith('file://') ? path.replace('file://', '') : path;
};

const normalizeHeaderMap = (headers: Record<string, unknown> = {}) => {
  const normalized: Record<string, string> = {};
  Object.entries(headers).forEach(([key, value]) => {
    normalized[key.toLowerCase()] = String(value ?? '');
  });
  return normalized;
};

const createAbortError = () => {
  const error = new Error('canceled') as Error & { code?: string };
  error.name = 'AbortError';
  error.code = 'ERR_CANCELED';
  return error;
};

const parseResultHeader = (rawHeader: string): ScanResult => {
  const candidates = [rawHeader];
  try {
    candidates.push(decodeURIComponent(rawHeader));
  } catch {
  }

  for (const candidate of candidates) {
    try {
      const parsed = JSON.parse(candidate);
      if (parsed && typeof parsed === 'object') {
        return parsed as ScanResult;
      }
    } catch {
    }
  }

  throw new Error('X-OMR-Result header parse edilemedi.');
};

const sanitizeBase64 = (value: string) => {
  const normalized = value.includes(',') ? value.split(',').pop() || '' : value;
  return normalized.replace(/\s+/g, '').trim();
};

const writeBase64TempImage = async (base64: string) => {
  const dirs = ReactNativeBlobUtil.fs.dirs;
  const tempDir = dirs.CacheDir || dirs.DocumentDir;
  const filePath = `${tempDir}/omr_result_temp_${Date.now()}_${Math.random().toString(36).slice(2, 7)}.jpg`;
  await ReactNativeBlobUtil.fs.writeFile(filePath, base64, 'base64');
  return filePath;
};

const toSafeToken = (value: string | number) => {
  const token = String(value ?? '').trim().replace(/[^a-zA-Z0-9_-]/g, '');
  return token || 'unknown';
};

export async function ensureResultDir() {
  const exists = await ReactNativeBlobUtil.fs.isDir(RESULT_IMAGES_DIR);
  if (!exists) {
    await ReactNativeBlobUtil.fs.mkdir(RESULT_IMAGES_DIR);
  }
}

export async function saveResultImage(
  tempPath: string,
  studentId: string | number,
  examId: string | number,
): Promise<string> {
  await ensureResultDir();

  const sourcePath = normalizeLocalPath(tempPath);
  const sourceExists = await ReactNativeBlobUtil.fs.exists(sourcePath);
  if (!sourceExists) {
    throw new Error('Geçici sonuç görseli bulunamadı.');
  }

  const timestamp = Date.now();
  const fileName = `student_${toSafeToken(studentId)}_exam_${toSafeToken(examId)}_${timestamp}.jpg`;
  const destPath = `${RESULT_IMAGES_DIR}/${fileName}`;

  await ReactNativeBlobUtil.fs.cp(sourcePath, destPath);
  if (sourcePath !== destPath) {
    await ReactNativeBlobUtil.fs.unlink(sourcePath).catch(() => {
    });
  }

  return destPath;
}

export async function deleteResultImage(imagePath?: string | null) {
  if (!imagePath) return;

  const normalizedPath = normalizeLocalPath(imagePath);
  const exists = await ReactNativeBlobUtil.fs.exists(normalizedPath);
  if (!exists) return;

  await ReactNativeBlobUtil.fs.unlink(normalizedPath).catch(() => {
  });
}

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
  let resizedPath = '';
  let tempResponsePath = '';
  let tempBase64ImagePath = '';
  let abortHandler: (() => void) | undefined;
  let task: any;

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

    resizedPath = normalizeLocalPath(resized.uri);
    const filename = resized.name || `photo_${Date.now()}.jpg`;

    task = ReactNativeBlobUtil.config({
      fileCache: true,
      appendExt: 'jpg',
    }).fetch(
      'POST',
      `${API_BASE_URL}/process`,
      { 'Content-Type': 'multipart/form-data' },
      [
        {
          name: 'file',
          filename,
          type: 'image/jpeg',
          data: ReactNativeBlobUtil.wrap(resizedPath),
        },
        {
          name: 'question_count',
          data: questionCount.toString(),
        },
      ],
    );

    if (signal?.aborted) {
      task.cancel();
      throw createAbortError();
    }

    abortHandler = () => {
      task.cancel();
    };
    signal?.addEventListener('abort', abortHandler);

    const response = await task;
    const info = response.info();
    const headers = normalizeHeaderMap((info?.headers as Record<string, unknown>) || {});
    const contentType = (headers['content-type'] || '').toLowerCase();
    const resultHeader = headers['x-omr-result'];
    tempResponsePath = normalizeLocalPath(response.path());

    let parsedResult: RawScanResult | null = null;

    if (resultHeader) {
      parsedResult = parseResultHeader(resultHeader) as RawScanResult;
    } else if (contentType.includes('application/json') || contentType.includes('text/json')) {
      const jsonBody = await response.text();
      parsedResult = JSON.parse(jsonBody) as RawScanResult;
    } else if (tempResponsePath) {
      try {
        const rawBody = await ReactNativeBlobUtil.fs.readFile(tempResponsePath, 'utf8');
        parsedResult = JSON.parse(rawBody) as RawScanResult;
      } catch {
        parsedResult = null;
      }
    }

    if (!parsedResult) {
      throw new Error('Tarama sonucu çözümlenemedi.');
    }

    const isJpegResponse = contentType.includes('image/jpeg') || contentType.includes('image/jpg') || Boolean(resultHeader);

    if (parsedResult.form_image_base64) {
      const sanitized = sanitizeBase64(parsedResult.form_image_base64);
      if (sanitized) {
        tempBase64ImagePath = await writeBase64TempImage(sanitized);
      }
    }

    if (!isJpegResponse && tempResponsePath) {
      await ReactNativeBlobUtil.fs.unlink(tempResponsePath).catch(() => {
      });
      tempResponsePath = '';
    }

    const { form_image_base64, ...resultWithoutBase64 } = parsedResult;
    const formImagePath = isJpegResponse ? tempResponsePath : (tempBase64ImagePath || undefined);

    return {
      ...(resultWithoutBase64 as ScanResult),
      formImagePath,
    };
  } catch (error: any) {
    const maybeMessage = String(error?.message || '').toLowerCase();
    if (error?.name === 'AbortError' || error?.code === 'ERR_CANCELED' || maybeMessage.includes('cancel')) {
      throw createAbortError();
    }

    if (tempResponsePath) {
      await ReactNativeBlobUtil.fs.unlink(tempResponsePath).catch(() => {
      });
    }

    if (tempBase64ImagePath) {
      await ReactNativeBlobUtil.fs.unlink(tempBase64ImagePath).catch(() => {
      });
    }

    console.error('Error processing form:', error);
    throw error;
  } finally {
    if (signal && abortHandler) {
      signal.removeEventListener('abort', abortHandler);
    }
    if (resizedPath) {
      await ReactNativeBlobUtil.fs.unlink(resizedPath).catch(() => {
      });
    }
  }
};
