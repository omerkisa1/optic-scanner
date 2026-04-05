import React, { useEffect, useRef, useState } from 'react';
import {
  View,
  Text,
  TouchableOpacity,
  StyleSheet,
  Alert,
  Platform,
  ScrollView,
  Animated,
  PanResponder,
  Dimensions,
  ActivityIndicator,
  PermissionsAndroid,
  Modal,
  TextInput,
} from 'react-native';
import {
  BookOpen,
  Key,
  Camera,
  Download,
  FileSpreadsheet,
  ChevronRight,
  CheckCircle,
  AlertCircle,
  FileText,
  FileX,
  Upload,
  ArrowRightLeft,
  Users,
  BarChart3,
} from 'lucide-react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import ReactNativeBlobUtil from 'react-native-blob-util';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import * as XLSX from 'xlsx';

import { useStore } from '../store/useStore';
import { RootStackParamList } from '../navigation/AppNavigator';
import { API_BASE_URL, processForm } from '../api/omrApi';
import { palette, radii } from '../theme/palette';
import { ClassRosterStudent } from '../types';

type Props = NativeStackScreenProps<RootStackParamList, 'GroupDetail'>;

const SCREEN_WIDTH = Dimensions.get('window').width;

const AVATAR_COLORS = ['#2F9E44', '#1E7F3B', '#0F6A36', '#3FAE55', '#2B8B4B', '#217140', '#46B362', '#2F7552'];
const getAvatarColor = (name: string) => {
  let hash = 0;
  for (let i = 0; i < name.length; i++) hash = name.charCodeAt(i) + ((hash << 5) - hash);
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
};

const normalizeStudentNo = (value: unknown) => String(value ?? '').replace(/\s+/g, '').trim();
const normalizeText = (value: unknown) => String(value ?? '').trim();
const getClassName = (group: any) => group?.course_name || group?.name || 'İsimsiz Sınıf';
const getQuestionCount = (group: any) => group?.question_count || group?.questionCount || 20;
const getAnswerKey = (group: any) => group?.answer_key || group?.answerKey || {};
const getCreatedAt = (group: any) => group?.created_at || group?.createdAt || Date.now();

const buildClassInfoLine = (group: any) => {
  const parts: string[] = [];
  if (group?.grade_level) parts.push(`${group.grade_level}. Sınıf`);
  if (group?.section) parts.push(`${group.section} Şubesi`);
  parts.push(`${getQuestionCount(group)} Soru`);
  return parts.join(' · ');
};

const normalizeHeaderKey = (value: string) => {
  return value
    .toLowerCase()
    .replace(/[ı]/g, 'i')
    .replace(/[ş]/g, 's')
    .replace(/[ğ]/g, 'g')
    .replace(/[ü]/g, 'u')
    .replace(/[ö]/g, 'o')
    .replace(/[ç]/g, 'c')
    .replace(/[^a-z0-9]/g, '');
};

const splitCsvLine = (line: string, delimiter: string) => {
  const values: string[] = [];
  let current = '';
  let inQuotes = false;

  for (let i = 0; i < line.length; i++) {
    const char = line[i];

    if (char === '"') {
      if (inQuotes && line[i + 1] === '"') {
        current += '"';
        i += 1;
      } else {
        inQuotes = !inQuotes;
      }
      continue;
    }

    if (char === delimiter && !inQuotes) {
      values.push(current.trim());
      current = '';
      continue;
    }

    current += char;
  }

  values.push(current.trim());
  return values;
};

const detectDelimiter = (line: string) => {
  const tabCount = (line.match(/\t/g) || []).length;
  const semicolonCount = (line.match(/;/g) || []).length;
  const commaCount = (line.match(/,/g) || []).length;

  if (tabCount >= semicolonCount && tabCount >= commaCount && tabCount > 0) return '\t';
  if (semicolonCount >= commaCount && semicolonCount > 0) return ';';
  return ',';
};

const sanitizePdfLine = (line: string) => {
  return line
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^\x20-\x7E]/g, ' ')
    .replace(/\\/g, '\\\\')
    .replace(/\(/g, '\\(')
    .replace(/\)/g, '\\)');
};

const buildPdfDocument = (allLines: string[]) => {
  const lines = allLines.map(line => sanitizePdfLine(line));
  const linesPerPage = 48;
  const pages: string[][] = [];

  for (let i = 0; i < lines.length; i += linesPerPage) {
    pages.push(lines.slice(i, i + linesPerPage));
  }
  if (pages.length === 0) pages.push(['SINIF SONUC RAPORU']);

  const objects: string[] = [];
  const pageRefs: string[] = [];
  const fontObjectId = 3 + pages.length * 2;

  objects[1] = '1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n';

  pages.forEach((pageLines, index) => {
    const pageObjectId = 3 + index * 2;
    const contentObjectId = pageObjectId + 1;
    pageRefs.push(`${pageObjectId} 0 R`);

    const commands: string[] = ['BT', '/F1 10 Tf', '50 800 Td'];
    pageLines.forEach((line, lineIndex) => {
      if (lineIndex > 0) commands.push('0 -15 Td');
      commands.push(`(${line}) Tj`);
    });
    commands.push('ET');

    const streamContent = commands.join('\n');
    objects[contentObjectId] = `${contentObjectId} 0 obj\n<< /Length ${streamContent.length} >>\nstream\n${streamContent}\nendstream\nendobj\n`;
    objects[pageObjectId] = `${pageObjectId} 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 ${fontObjectId} 0 R >> >> /Contents ${contentObjectId} 0 R >>\nendobj\n`;
  });

  objects[2] = `2 0 obj\n<< /Type /Pages /Kids [${pageRefs.join(' ')}] /Count ${pages.length} >>\nendobj\n`;
  objects[fontObjectId] = `${fontObjectId} 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n`;

  let pdf = '%PDF-1.4\n';
  const offsets: number[] = [];
  for (let objectId = 1; objectId <= fontObjectId; objectId++) {
    offsets[objectId] = pdf.length;
    pdf += objects[objectId];
  }

  const xrefOffset = pdf.length;
  pdf += `xref\n0 ${fontObjectId + 1}\n`;
  pdf += '0000000000 65535 f \n';
  for (let objectId = 1; objectId <= fontObjectId; objectId++) {
    pdf += `${String(offsets[objectId]).padStart(10, '0')} 00000 n \n`;
  }
  pdf += `trailer\n<< /Size ${fontObjectId + 1} /Root 1 0 R >>\nstartxref\n${xrefOffset}\n%%EOF`;

  return pdf;
};

const SwipeableResultItem = ({
  res,
  score,
  onPress,
  onDelete,
  onTransfer,
  transferEnabled,
}: {
  res: any;
  score: number;
  onPress: () => void;
  onDelete: () => void;
  onTransfer: () => void;
  transferEnabled: boolean;
}) => {
  const [swipeAnim] = useState(new Animated.Value(0));

  const panResponder = PanResponder.create({
    onStartShouldSetPanResponder: () => false,
    onMoveShouldSetPanResponder: (_evt, gestureState) => {
      return Math.abs(gestureState.dx) > 20 && Math.abs(gestureState.dx) > Math.abs(gestureState.dy);
    },
    onPanResponderMove: (_evt, gestureState) => {
      if (gestureState.dx < 0) swipeAnim.setValue(gestureState.dx);
    },
    onPanResponderRelease: (_evt, gestureState) => {
      if (gestureState.dx < -SCREEN_WIDTH * 0.3) {
        Animated.timing(swipeAnim, {
          toValue: -SCREEN_WIDTH,
          duration: 200,
          useNativeDriver: true,
        }).start(() => {
          onDelete();
        });
      } else {
        Animated.spring(swipeAnim, {
          toValue: 0,
          useNativeDriver: true,
        }).start();
      }
    },
  });

  return (
    <View style={styles.swipeContainer}>
      <View style={styles.deleteBackground}>
        <Text style={styles.deleteText}>Sil</Text>
      </View>
      <Animated.View style={{ transform: [{ translateX: swipeAnim }] }} {...panResponder.panHandlers}>
        <View style={styles.resultCard}>
          <TouchableOpacity style={styles.resultMainArea} onPress={onPress} activeOpacity={0.75}>
            <View style={styles.resultLeft}>
              <View style={[styles.resultAvatar, { backgroundColor: getAvatarColor(res.name || '?') }]}>
                <Text style={styles.resultAvatarText}>{(res.name || '?')[0].toUpperCase()}</Text>
              </View>
              <View style={styles.resultInfo}>
                <Text style={styles.resultName} numberOfLines={1}>{res.name}</Text>
                <Text style={styles.resultNo}>No: {res.studentNumber || 'Belirtilmemiş'}</Text>
              </View>
            </View>
            <View style={styles.resultRight}>
              <View style={styles.resultStats}>
                <Text style={styles.statCorrect}>{res.correct}D</Text>
                <Text style={styles.statWrong}>{res.wrong}Y</Text>
                <Text style={styles.statBlank}>{res.blank}B</Text>
              </View>
              <Text style={styles.resultScore}>{score.toFixed(2)}</Text>
            </View>
            <ChevronRight size={18} color={palette.muted} />
          </TouchableOpacity>

          {transferEnabled ? (
            <TouchableOpacity style={styles.transferBtn} onPress={onTransfer} activeOpacity={0.85}>
              <ArrowRightLeft size={14} color={palette.accent} />
              <Text style={styles.transferBtnText}>Sınıfa Aktar</Text>
            </TouchableOpacity>
          ) : null}
        </View>
      </Animated.View>
    </View>
  );
};

export const GroupDetailScreen = ({ route, navigation }: Props) => {
  const { groupId } = route.params;
  const {
    groups,
    addStudentResult,
    updateStudentResult,
    removeStudentResult,
    addRosterStudents,
    transferStudentResult,
  } = useStore();

  const processedUriRef = useRef<string | null>(null);

  const [downloading, setDownloading] = useState(false);
  const [exportingExcel, setExportingExcel] = useState(false);
  const [exportingPdf, setExportingPdf] = useState(false);

  const [importModalVisible, setImportModalVisible] = useState(false);
  const [importingRoster, setImportingRoster] = useState(false);
  const [importFileCandidates, setImportFileCandidates] = useState<string[]>([]);
  const [importPathInput, setImportPathInput] = useState('');
  const [importPreview, setImportPreview] = useState<ClassRosterStudent[]>([]);

  const [transferModalVisible, setTransferModalVisible] = useState(false);
  const [selectedResultId, setSelectedResultId] = useState('');
  const [selectedTargetGroupId, setSelectedTargetGroupId] = useState('');

  const group = groups.find(item => item.id === groupId);
  const otherGroups = groups.filter(item => item.id !== groupId);

  const className = group ? getClassName(group) : '';
  const questionCount = group ? getQuestionCount(group) : 20;
  const answerKey = group ? getAnswerKey(group) : {};

  const completedResults = (group?.results || [])
    .filter((result: any) => !result.pending)
    .sort((a: any, b: any) => (b.scannedAt || 0) - (a.scannedAt || 0));
  const pendingResults = (group?.results || []).filter((result: any) => result.pending);

  const classStudents = group?.roster || [];
  const hasAnswerKey = Object.keys(answerKey).length > 0;

  const latestResultByNo = new Map<string, any>();
  completedResults.forEach((result: any) => {
    const key = normalizeStudentNo(result.studentNumber);
    if (key && !latestResultByNo.has(key)) latestResultByNo.set(key, result);
  });

  const scannedStudentCount = classStudents.length > 0
    ? classStudents.filter((student: any) => latestResultByNo.has(normalizeStudentNo(student.student_number))).length
    : completedResults.length;
  const totalStudentCount = classStudents.length > 0 ? classStudents.length : completedResults.length;
  const remainingStudentCount = Math.max(totalStudentCount - scannedStudentCount, 0);

  const averageScore = completedResults.length > 0
    ? completedResults.reduce((sum: number, item: any) => sum + Number(item.score || 0), 0) / completedResults.length
    : 0;
  const maxScore = completedResults.length > 0
    ? Math.max(...completedResults.map((item: any) => Number(item.score || 0)))
    : 0;
  const minScore = completedResults.length > 0
    ? Math.min(...completedResults.map((item: any) => Number(item.score || 0)))
    : 0;

  const tableRows = classStudents.length > 0
    ? classStudents.map((student: any) => {
      const key = normalizeStudentNo(student.student_number);
      const result = latestResultByNo.get(key);
      return {
        key: `student-${key}`,
        full_name: student.full_name,
        student_number: student.student_number,
        scanned: Boolean(result),
        score: Number(result?.score || 0),
        correct: Number(result?.correct || 0),
        wrong: Number(result?.wrong || 0),
        blank: Number(result?.blank || 0),
      };
    })
    : completedResults.map((result: any, index: number) => ({
      key: `result-${result.id || index}`,
      full_name: result.name || 'Bilinmeyen',
      student_number: result.studentNumber || '-',
      scanned: true,
      score: Number(result.score || 0),
      correct: Number(result.correct || 0),
      wrong: Number(result.wrong || 0),
      blank: Number(result.blank || 0),
    }));

  const questionAnalysis = Array.from({ length: questionCount }, (_, index) => {
    const qNo = String(index + 1);
    let correct = 0;
    let wrong = 0;
    let blank = 0;

    completedResults.forEach((result: any) => {
      const userAnswer = normalizeText(result.answers?.[qNo]);
      const correctAnswer = normalizeText(answerKey[qNo]);

      if (!userAnswer || userAnswer === 'Boş') {
        blank += 1;
        return;
      }

      if (userAnswer.includes(',')) {
        wrong += 1;
        return;
      }

      if (correctAnswer && userAnswer === correctAnswer) {
        correct += 1;
      } else {
        wrong += 1;
      }
    });

    const total = completedResults.length || 1;
    return {
      qNo,
      correct,
      wrong,
      blank,
      correctRate: ((correct / total) * 100).toFixed(2),
    };
  });

  const processFormInBackground = async (imageUri: string, pendingId: string) => {
    if (!group) return;

    const controller = new AbortController();
    const timeoutId = setTimeout(() => {
      controller.abort();
      removeStudentResult(group.id, pendingId);
    }, 60000);

    try {
      const res = await processForm(imageUri, questionCount, controller.signal);
      clearTimeout(timeoutId);

      if (res.error || res.status === 'error') {
        updateStudentResult(group.id, pendingId, {
          name: 'Hata Oluştu',
          pending: false,
        });
        return;
      }

      let correct = 0;
      let wrong = 0;
      let blank = 0;
      const answers = res.answers || {};

      Object.entries(answers).forEach(([qNo, userAns]) => {
        const correctAns = answerKey[qNo];
        if (!userAns || userAns === 'Boş') blank += 1;
        else if (typeof userAns === 'string' && userAns.includes(',')) wrong += 1;
        else if (correctAns && userAns === correctAns) correct += 1;
        else wrong += 1;
      });

      const answeredCount = Object.keys(answers).length;
      if (answeredCount < questionCount) blank += questionCount - answeredCount;

      const score = parseFloat(((correct / (questionCount || 1)) * 100).toFixed(2));

      updateStudentResult(group.id, pendingId, {
        name: (res.student_info as any)?.student_name || res.student_info?.name || 'Bilinmeyen',
        studentNumber: res.student_info?.student_number || 'Bilinmiyor',
        correct,
        wrong,
        blank,
        score,
        answers: res.answers || {},
        pending: false,
      });
    } catch (error: any) {
      clearTimeout(timeoutId);
      if (error?.name === 'AbortError' || error?.code === 'ERR_CANCELED' || error?.message === 'canceled') {
        removeStudentResult(group.id, pendingId);
        return;
      }
      updateStudentResult(group.id, pendingId, {
        name: 'Bağlantı Hatası',
        pending: false,
      });
    }
  };

  const handleImageCaptured = (imageUri: string) => {
    if (!group) return;

    const pendingId = Math.random().toString(36).slice(2, 11);
    addStudentResult(group.id, {
      id: pendingId,
      name: 'Okunuyor...',
      studentNumber: '',
      correct: 0,
      wrong: 0,
      blank: 0,
      score: 0,
      answers: {},
      scannedAt: Date.now(),
      pending: true,
    });

    processFormInBackground(imageUri, pendingId);
  };

  useEffect(() => {
    if (!group) return;

    const now = Date.now();
    const zombies = (group.results || []).filter(
      (item: any) => item.pending && (now - (item.scannedAt || 0)) > 90000
    );

    zombies.forEach((zombie: any) => {
      removeStudentResult(group.id, zombie.id);
    });
  }, [group?.id]);

  useEffect(() => {
    const uri = route.params.capturedImageUri;
    if (uri && uri !== processedUriRef.current && group) {
      processedUriRef.current = uri;
      handleImageCaptured(uri);
      navigation.setParams({ capturedImageUri: undefined } as any);
    }
  }, [route.params.capturedImageUri, group?.id]);

  const parseCsvRows = (content: string) => {
    const lines = content
      .replace(/\r\n/g, '\n')
      .replace(/\r/g, '\n')
      .split('\n')
      .filter(line => normalizeText(line).length > 0);

    if (lines.length === 0) return [] as Record<string, unknown>[];

    const delimiter = detectDelimiter(lines[0]);
    const firstValues = splitCsvLine(lines[0], delimiter);
    const firstHeaders = firstValues.map(value => normalizeHeaderKey(value));

    const headerLooksValid = firstHeaders.some(header =>
      ['adsoyad', 'isimsoyisim', 'ogrenciadi', 'ogrencino', 'numara', 'studentname', 'studentnumber', 'sinif', 'sube'].includes(header)
    );

    const headers = headerLooksValid
      ? firstValues.map((value, index) => normalizeHeaderKey(value) || `col${index + 1}`)
      : firstValues.map((_, index) => `col${index + 1}`);
    const startLine = headerLooksValid ? 1 : 0;

    const rows: Record<string, unknown>[] = [];
    for (let i = startLine; i < lines.length; i++) {
      const values = splitCsvLine(lines[i], delimiter);
      const row: Record<string, unknown> = {};
      headers.forEach((header, index) => {
        row[header] = normalizeText(values[index]);
      });
      rows.push(row);
    }

    return rows;
  };

  const parseRowsToRoster = (rows: Record<string, unknown>[]) => {
    const mapped = new Map<string, ClassRosterStudent>();

    rows.forEach((rawRow, index) => {
      const row: Record<string, string> = {};
      Object.entries(rawRow || {}).forEach(([key, value]) => {
        row[normalizeHeaderKey(key)] = normalizeText(value);
      });

      const pick = (keys: string[]) => {
        for (const key of keys) {
          const value = normalizeText(row[key]);
          if (value) return value;
        }
        return '';
      };

      let fullName = pick(['adsoyad', 'adisoyadi', 'isimsoyisim', 'ogrenciadi', 'ogrenciadisoyadi', 'studentname', 'name', 'fullname', 'col1']);
      if (!fullName) {
        const firstName = pick(['ad', 'isim', 'firstname']);
        const lastName = pick(['soyad', 'surname', 'lastname']);
        fullName = normalizeText(`${firstName} ${lastName}`);
      }

      const studentNumber = pick(['ogrencino', 'numara', 'no', 'studentnumber', 'studentno', 'schoolnumber', 'col2']);
      const normalizedNo = normalizeStudentNo(studentNumber);
      if (!normalizedNo) return;

      const grade = pick(['sinif', 'sinifduzeyi', 'grade', 'gradelevel']) || normalizeText(group?.grade_level);
      const section = pick(['sube', 'section']) || normalizeText(group?.section);

      mapped.set(normalizedNo, {
        id: `preview-${index}-${normalizedNo}`,
        full_name: fullName || `Öğrenci ${normalizedNo}`,
        student_number: normalizeText(studentNumber),
        grade_level: grade || undefined,
        section: section || undefined,
        created_at: Date.now(),
      });
    });

    return Array.from(mapped.values());
  };

  const isRosterFile = (filePath: string) => /\.(xlsx|xls|csv)$/i.test(filePath);

  const loadImportCandidates = async () => {
    if (Platform.OS === 'android' && Number(Platform.Version) < 33) {
      const granted = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.READ_EXTERNAL_STORAGE);
      if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
        Alert.alert('İzin Gerekli', 'Dosya listesine erişmek için depolama izni gerekir.');
        return;
      }
    }

    setImportingRoster(true);
    try {
      const dirs = ReactNativeBlobUtil.fs.dirs;
      const searchDirs = [dirs.DownloadDir, dirs.DocumentDir].filter(Boolean);
      const found: string[] = [];

      for (const dir of searchDirs) {
        try {
          const names = await ReactNativeBlobUtil.fs.ls(dir);
          names.forEach(name => {
            const fullPath = `${dir}/${name}`;
            if (isRosterFile(fullPath)) found.push(fullPath);
          });
        } catch {
        }
      }

      const unique = Array.from(new Set(found));
      unique.sort((a, b) => b.localeCompare(a, 'tr'));
      setImportFileCandidates(unique);
    } finally {
      setImportingRoster(false);
    }
  };

  const previewImportFile = async (candidatePath?: string) => {
    const filePath = normalizeText(candidatePath || importPathInput);
    if (!filePath) {
      Alert.alert('Uyarı', 'Lütfen bir dosya seçin veya dosya yolunu girin.');
      return;
    }
    if (!isRosterFile(filePath)) {
      Alert.alert('Hata', 'Yalnızca .xlsx, .xls veya .csv dosyaları desteklenir.');
      return;
    }

    setImportingRoster(true);
    try {
      let rows: Record<string, unknown>[] = [];

      if (/\.csv$/i.test(filePath)) {
        const csvContent = await ReactNativeBlobUtil.fs.readFile(filePath, 'utf8');
        rows = parseCsvRows(csvContent);
      } else {
        const base64 = await ReactNativeBlobUtil.fs.readFile(filePath, 'base64');
        const workbook = XLSX.read(base64, { type: 'base64' });
        const firstSheetName = workbook.SheetNames[0];
        if (!firstSheetName) throw new Error('Dosyada okunabilir bir sayfa bulunamadı.');
        const worksheet = workbook.Sheets[firstSheetName];

        rows = XLSX.utils.sheet_to_json<Record<string, unknown>>(worksheet, { defval: '', raw: false });
        if (rows.length === 0) {
          const matrix = XLSX.utils.sheet_to_json<any[]>(worksheet, { header: 1, defval: '', raw: false }) as any[];
          rows = matrix
            .filter((line: any) => Array.isArray(line) && line.some(cell => normalizeText(cell).length > 0))
            .map((line: any[]) => ({
              col1: normalizeText(line[0]),
              col2: normalizeText(line[1]),
              col3: normalizeText(line[2]),
              col4: normalizeText(line[3]),
            }));
        }
      }

      const preview = parseRowsToRoster(rows);
      if (preview.length === 0) {
        Alert.alert('Uyarı', 'Dosyadan geçerli öğrenci numarası içeren satır bulunamadı.');
        setImportPreview([]);
        return;
      }

      setImportPathInput(filePath);
      setImportPreview(preview);
    } catch (error: any) {
      Alert.alert('Hata', error?.message || 'Dosya okunamadı.');
    } finally {
      setImportingRoster(false);
    }
  };

  const closeImportModal = () => {
    setImportModalVisible(false);
    setImportPathInput('');
    setImportPreview([]);
  };

  const runRosterImport = (mode: 'skip' | 'replace') => {
    if (!group || importPreview.length === 0) return;
    const summary = addRosterStudents(group.id, importPreview, mode);
    Alert.alert('İçe Aktarma Tamamlandı', `Eklenen: ${summary.added}\nGüncellenen: ${summary.replaced}\nAtlanan: ${summary.skipped}`);
    closeImportModal();
  };

  const handleConfirmImport = () => {
    if (!group || importPreview.length === 0) {
      Alert.alert('Uyarı', 'İçe aktarım için önce önizleme oluşturun.');
      return;
    }

    const existingNumbers = new Set((group.roster || []).map((student: any) => normalizeStudentNo(student.student_number)));
    const conflictCount = importPreview.reduce((count, student) => {
      const key = normalizeStudentNo(student.student_number);
      return existingNumbers.has(key) ? count + 1 : count;
    }, 0);

    if (conflictCount > 0) {
      Alert.alert(
        'Numara Çakışması Bulundu',
        `${conflictCount} öğrenci numarası mevcut listede zaten var. Çakışan kayıtlar güncellensin mi?`,
        [
          { text: 'Sadece Yenileri Ekle', style: 'cancel', onPress: () => runRosterImport('skip') },
          { text: 'Çakışanları Güncelle', onPress: () => runRosterImport('replace') },
        ]
      );
      return;
    }

    runRosterImport('skip');
  };

  const openTransferModal = (result: any) => {
    if (otherGroups.length === 0) {
      Alert.alert('Uyarı', 'Aktarım için en az bir farklı sınıf oluşturulmalı.');
      return;
    }

    const studentNo = normalizeStudentNo(result.studentNumber);
    const suggested = otherGroups.find(other => {
      if (!studentNo) return false;
      const rosterMatch = (other.roster || []).some((student: any) => normalizeStudentNo(student.student_number) === studentNo);
      const resultMatch = (other.results || []).some((item: any) => normalizeStudentNo(item.studentNumber) === studentNo);
      return rosterMatch || resultMatch;
    });

    setSelectedResultId(result.id);
    setSelectedTargetGroupId(suggested?.id || otherGroups[0]?.id || '');
    setTransferModalVisible(true);
  };

  const closeTransferModal = () => {
    setTransferModalVisible(false);
    setSelectedResultId('');
    setSelectedTargetGroupId('');
  };

  const executeTransfer = (mode: 'skip' | 'replace') => {
    if (!group || !selectedResultId || !selectedTargetGroupId) return;

    const summary = transferStudentResult(group.id, selectedTargetGroupId, selectedResultId, mode);
    if (summary.ok) {
      const target = groups.find(item => item.id === selectedTargetGroupId);
      Alert.alert('Başarılı', `Öğrenci ${getClassName(target)} sınıfına aktarıldı.`);
      closeTransferModal();
      return;
    }

    if (summary.reason === 'duplicate-skipped' && mode === 'skip') {
      Alert.alert(
        'Aynı Numara Bulundu',
        'Hedef sınıfta aynı öğrenci numarasına sahip kayıt var. Mevcut kayıt güncellensin mi?',
        [
          { text: 'Vazgeç', style: 'cancel' },
          { text: 'Üzerine Yaz', onPress: () => executeTransfer('replace') },
        ]
      );
      return;
    }

    Alert.alert('Hata', 'Aktarım sırasında beklenmeyen bir sorun oluştu.');
  };

  const handleScan = () => {
    if (!group) return;
    navigation.navigate('Camera', {
      groupId: group.id,
      groupName: className,
      questionCount,
    });
  };

  const handleDownloadForm = async () => {
    if (!group || downloading) return;

    setDownloading(true);
    try {
      if (Platform.OS === 'android') {
        const permission = Number(Platform.Version) >= 33
          ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES
          : PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE;
        const granted = await PermissionsAndroid.request(permission);
        if (granted !== PermissionsAndroid.RESULTS.GRANTED) {
          Alert.alert('İzin Reddedildi', 'Galeriye kaydetmek için depolama izni gereklidir.');
          setDownloading(false);
          return;
        }
      }

      const dirs = ReactNativeBlobUtil.fs.dirs;
      const tempFileUrl = `${dirs.DocumentDir}/optik_form_${Date.now()}.png`;
      const downloadResult = await ReactNativeBlobUtil.config({
        fileCache: true,
        path: tempFileUrl,
      }).fetch('GET', `${API_BASE_URL}/generate_form?question_count=${questionCount}`);

      if (downloadResult.info().status === 200) {
        await CameraRoll.save(`file://${downloadResult.path()}`, { type: 'photo' });
        Alert.alert('Başarılı', 'Boş şablon galerisine kaydedildi.');
      } else {
        Alert.alert('Hata', 'Şablon indirilirken bir sorun oluştu.');
      }
    } catch (error: any) {
      Alert.alert('Hata', error?.message || 'Resim kaydedilemedi.');
    } finally {
      setDownloading(false);
    }
  };

  const handleExportExcel = async () => {
    if (!group) return;
    if (completedResults.length === 0) {
      Alert.alert('Uyarı', 'Dışa aktarılacak sonuç bulunmuyor.');
      return;
    }

    setExportingExcel(true);
    try {
      const sortedResults = [...completedResults].sort((a: any, b: any) =>
        normalizeStudentNo(a.studentNumber).localeCompare(normalizeStudentNo(b.studentNumber), 'tr')
      );

      const rosterByNo = new Map<string, any>();
      classStudents.forEach((student: any) => {
        const key = normalizeStudentNo(student.student_number);
        if (key) rosterByNo.set(key, student);
      });

      const answerColumns = Array.from({ length: questionCount }, (_, i) => `S${i + 1}`);
      const headerRow = ['Sıra', 'Öğrenci No', 'Öğrenci Adı', 'Sınıf Düzeyi', 'Şube', 'Doğru', 'Yanlış', 'Boş', 'Net', 'Puan', ...answerColumns];

      const dataRows = sortedResults.map((result: any, index: number) => {
        const key = normalizeStudentNo(result.studentNumber);
        const rosterItem = rosterByNo.get(key);
        const grade = rosterItem?.grade_level || group.grade_level || '';
        const section = rosterItem?.section || group.section || '';
        const net = (Number(result.correct || 0) - Number(result.wrong || 0) * 0.25).toFixed(2);
        const answers = Array.from({ length: questionCount }, (_, qIndex) => {
          const answer = normalizeText(result.answers?.[String(qIndex + 1)]);
          return answer || 'B';
        });

        return [
          index + 1,
          result.studentNumber || '',
          result.name || '',
          grade,
          section,
          Number(result.correct || 0),
          Number(result.wrong || 0),
          Number(result.blank || 0),
          net,
          Number(result.score || 0).toFixed(2),
          ...answers,
        ];
      });

      const reportRows: any[][] = [
        ['Sınıf Sonuç Raporu'],
        ['Sınıf', className],
        ['Bilgi', buildClassInfoLine(group)],
        ['Soru Sayısı', questionCount, 'Toplam Öğrenci', totalStudentCount, 'Taranan', scannedStudentCount],
        ['Rapor Tarihi', new Date().toLocaleString('tr-TR')],
        [],
        ['Öğrenci Sonuçları'],
        headerRow,
        ...dataRows,
        [],
        ['Genel İstatistikler'],
        ['Ortalama Puan', averageScore.toFixed(2)],
        ['En Yüksek Puan', maxScore.toFixed(2)],
        ['En Düşük Puan', minScore.toFixed(2)],
        ['Kalan Öğrenci', remainingStudentCount],
        [],
        ['Soru Analizi'],
        ['Soru', 'Doğru', 'Yanlış', 'Boş', 'Doğru %'],
        ...questionAnalysis.map(item => [item.qNo, item.correct, item.wrong, item.blank, item.correctRate]),
      ];

      const worksheet = XLSX.utils.aoa_to_sheet(reportRows);
      worksheet['!cols'] = [
        { wch: 7 },
        { wch: 14 },
        { wch: 25 },
        { wch: 13 },
        { wch: 8 },
        { wch: 8 },
        { wch: 8 },
        { wch: 8 },
        { wch: 8 },
        { wch: 8 },
        ...Array.from({ length: questionCount }, () => ({ wch: 5 })),
      ];

      const workbook = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(workbook, worksheet, className.slice(0, 28) || 'Sinif');

      const base64 = XLSX.write(workbook, { type: 'base64', bookType: 'xlsx' });
      const dirs = ReactNativeBlobUtil.fs.dirs;
      const outputDir = dirs.DownloadDir || dirs.DocumentDir;
      const safeName = className.replace(/[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ ]/g, '_').trim() || 'sinif';
      const fileName = `${safeName}_gelismis_rapor.xlsx`;
      const filePath = `${outputDir}/${fileName}`;

      await ReactNativeBlobUtil.fs.writeFile(filePath, base64, 'base64');
      if (Platform.OS === 'android') {
        ReactNativeBlobUtil.android.actionViewIntent(filePath, 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet');
      }

      Alert.alert('Başarılı', `Excel raporu kaydedildi:\n${fileName}`);
    } catch (error: any) {
      Alert.alert('Hata', error?.message || 'Excel dosyası oluşturulamadı.');
    } finally {
      setExportingExcel(false);
    }
  };

  const handleExportPdf = async () => {
    if (!group) return;
    if (completedResults.length === 0) {
      Alert.alert('Uyarı', 'PDF için en az bir tarama sonucu olmalı.');
      return;
    }

    setExportingPdf(true);
    try {
      const lines: string[] = [
        'SINIF SONUC RAPORU',
        `Sinif: ${className}`,
        `Bilgi: ${buildClassInfoLine(group)}`,
        `Soru Sayisi: ${questionCount}`,
        `Toplam Ogrenci: ${totalStudentCount}`,
        `Taranan Ogrenci: ${scannedStudentCount}`,
        `Kalan Ogrenci: ${remainingStudentCount}`,
        `Ortalama Puan: ${averageScore.toFixed(2)}`,
        '',
        'OGRENCI SONUCLARI',
      ];

      completedResults.forEach((result: any, index: number) => {
        lines.push(`${index + 1}. ${result.studentNumber || '-'} | ${result.name || 'Bilinmeyen'} | D:${result.correct} Y:${result.wrong} B:${result.blank} | P:${Number(result.score || 0).toFixed(2)}`);
      });

      lines.push('');
      lines.push('SORU ANALIZI');
      questionAnalysis.forEach(item => {
        lines.push(`Soru ${item.qNo}: D=${item.correct} Y=${item.wrong} B=${item.blank} Basari=${item.correctRate}%`);
      });

      const pdf = buildPdfDocument(lines);
      const dirs = ReactNativeBlobUtil.fs.dirs;
      const outputDir = dirs.DownloadDir || dirs.DocumentDir;
      const safeName = className.replace(/[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ ]/g, '_').trim() || 'sinif';
      const fileName = `${safeName}_rapor.pdf`;
      const filePath = `${outputDir}/${fileName}`;

      await ReactNativeBlobUtil.fs.writeFile(filePath, pdf, 'utf8');
      if (Platform.OS === 'android') {
        ReactNativeBlobUtil.android.actionViewIntent(filePath, 'application/pdf');
      }

      Alert.alert('Başarılı', `PDF raporu kaydedildi:\n${fileName}`);
    } catch (error: any) {
      Alert.alert('Hata', error?.message || 'PDF dosyası oluşturulamadı.');
    } finally {
      setExportingPdf(false);
    }
  };

  const handleResultPress = (result: any) => {
    if (!group || result.pending) return;
    navigation.navigate('ResultDetail', { groupId: group.id, resultId: result.id });
  };

  const selectedTransferResult = (group?.results || []).find((item: any) => item.id === selectedResultId);

  if (!group) {
    return (
      <View style={[styles.container, styles.centered]}>
        <Text style={styles.emptyText}>Sınıf bulunamadı.</Text>
      </View>
    );
  }

  return (
    <>
      <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
        <View style={styles.headerCard}>
          <View style={styles.headerTop}>
            <View style={styles.headerIconBox}>
              <BookOpen size={24} color={palette.primary} />
            </View>
            <View style={styles.headerInfo}>
              <Text style={styles.headerTitle}>{className}</Text>
              <Text style={styles.headerSubtitle}>{buildClassInfoLine(group)}</Text>
              <Text style={styles.headerMetaDate}>Oluşturulma: {new Date(getCreatedAt(group)).toLocaleDateString('tr-TR')}</Text>
            </View>
          </View>

          <View style={styles.statusRow}>
            {hasAnswerKey ? (
              <View style={[styles.statusBadge, styles.statusGreen]}>
                <CheckCircle size={12} color={palette.positive} style={{ marginRight: 4 }} />
                <Text style={styles.statusGreenText}>Cevap Anahtarı Hazır</Text>
              </View>
            ) : (
              <View style={[styles.statusBadge, styles.statusOrange]}>
                <AlertCircle size={12} color={palette.warning} style={{ marginRight: 4 }} />
                <Text style={styles.statusOrangeText}>Cevap Anahtarı Girilmemiş</Text>
              </View>
            )}
            <View style={styles.statusBadge}>
              <Users size={12} color={palette.muted} style={{ marginRight: 4 }} />
              <Text style={styles.statusText}>{scannedStudentCount}/{totalStudentCount || scannedStudentCount} Tarandı</Text>
            </View>
          </View>
        </View>

        <View style={styles.actionsCard}>
          <View style={styles.actionRow}>
            <TouchableOpacity style={styles.actionBtn} onPress={() => navigation.navigate('ExamConfig', { exam: group as any })} activeOpacity={0.75}>
              <Key size={16} color={palette.ink} />
              <Text style={styles.actionBtnText}>Cevap Anahtarı</Text>
            </TouchableOpacity>

            <TouchableOpacity style={[styles.actionBtn, styles.scanBtn]} onPress={handleScan} activeOpacity={0.85}>
              <Camera size={16} color={palette.white} />
              <Text style={styles.scanBtnText}>Tara</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.actionRow}>
            <TouchableOpacity
              style={[styles.actionBtn, styles.outlineActionBtn, downloading && styles.downloadBtnDisabled]}
              onPress={handleDownloadForm}
              activeOpacity={0.75}
              disabled={downloading}
            >
              {downloading ? <ActivityIndicator size="small" color={palette.accent} /> : <Download size={16} color={palette.accent} />}
              <Text style={styles.outlineActionBtnText}>{downloading ? 'İndiriliyor...' : 'Boş Şablon İndir'}</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.actionBtn, styles.outlineActionBtn]}
              onPress={async () => {
                setImportModalVisible(true);
                setImportPreview([]);
                await loadImportCandidates();
              }}
              activeOpacity={0.85}
            >
              <Upload size={16} color={palette.accent} />
              <Text style={styles.outlineActionBtnText}>Öğrenci İçe Aktar</Text>
            </TouchableOpacity>
          </View>

          <View style={styles.actionRow}>
            <TouchableOpacity
              style={[styles.actionBtn, styles.reportBtn, exportingExcel && styles.downloadBtnDisabled]}
              onPress={handleExportExcel}
              activeOpacity={0.85}
              disabled={exportingExcel}
            >
              {exportingExcel ? <ActivityIndicator size="small" color={palette.white} /> : <FileSpreadsheet size={16} color={palette.white} />}
              <Text style={styles.reportBtnText}>Gelişmiş Excel</Text>
            </TouchableOpacity>

            <TouchableOpacity
              style={[styles.actionBtn, styles.reportBtn, exportingPdf && styles.downloadBtnDisabled]}
              onPress={handleExportPdf}
              activeOpacity={0.85}
              disabled={exportingPdf}
            >
              {exportingPdf ? <ActivityIndicator size="small" color={palette.white} /> : <FileText size={16} color={palette.white} />}
              <Text style={styles.reportBtnText}>PDF Raporu</Text>
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.summaryCard}>
          <View style={styles.summaryHeader}>
            <BarChart3 size={16} color={palette.primary} />
            <Text style={styles.summaryTitle}>Sınıf İstatistikleri</Text>
          </View>
          <View style={styles.summaryGrid}>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryValue}>{totalStudentCount || 0}</Text>
              <Text style={styles.summaryLabel}>Toplam Öğrenci</Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryValue}>{scannedStudentCount}</Text>
              <Text style={styles.summaryLabel}>Taranan</Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryValue}>{remainingStudentCount}</Text>
              <Text style={styles.summaryLabel}>Kalan</Text>
            </View>
            <View style={styles.summaryItem}>
              <Text style={styles.summaryValue}>{averageScore.toFixed(2)}</Text>
              <Text style={styles.summaryLabel}>Ort. Puan</Text>
            </View>
          </View>
          <Text style={styles.summaryMeta}>En yüksek: {maxScore.toFixed(2)} · En düşük: {minScore.toFixed(2)}</Text>
        </View>

        <View style={styles.tableCard}>
          <Text style={styles.sectionTitle}>Sınıf Sonuç Tablosu</Text>
          {tableRows.length === 0 ? (
            <Text style={styles.emptyTableText}>Henüz sonuç veya öğrenci listesi bulunmuyor.</Text>
          ) : (
            tableRows.slice(0, 120).map((row: any) => (
              <View key={row.key} style={styles.tableRow}>
                <View style={styles.tableStudentCell}>
                  <Text style={styles.tableStudentName} numberOfLines={1}>{row.full_name || 'Bilinmeyen'}</Text>
                  <Text style={styles.tableStudentNo}>No: {row.student_number || '-'}</Text>
                </View>
                <View style={styles.tableStatusCell}>
                  <Text style={[styles.tableStatusText, row.scanned ? styles.tableStatusDone : styles.tableStatusPending]}>
                    {row.scanned ? 'Tarandı' : 'Bekliyor'}
                  </Text>
                </View>
                <View style={styles.tableScoreCell}>
                  <Text style={styles.tableScoreText}>{row.scanned ? row.score.toFixed(2) : '-'}</Text>
                  <Text style={styles.tableScoreMeta}>{row.scanned ? `${row.correct}D ${row.wrong}Y ${row.blank}B` : ''}</Text>
                </View>
              </View>
            ))
          )}
          {tableRows.length > 120 ? <Text style={styles.tableLimitText}>Performans için ilk 120 satır gösteriliyor.</Text> : null}
        </View>

        <View style={styles.resultsSection}>
          <Text style={styles.sectionTitle}>Tarama Sonuçları</Text>

          {pendingResults.map((result: any, index: number) => (
            <View key={result.id || `pending-${index}`} style={[styles.resultCardPendingWrap, styles.resultCardPending]}>
              <ActivityIndicator size="small" color={palette.warning} style={{ marginRight: 12 }} />
              <View style={styles.resultInfo}>
                <Text style={styles.pendingText}>Taranıyor...</Text>
                <Text style={styles.pendingSubtext}>Optik form okunuyor</Text>
              </View>
            </View>
          ))}

          {completedResults.length === 0 && pendingResults.length === 0 ? (
            <View style={styles.emptyResultsBox}>
              <View style={styles.emptyResultsIconWrap}>
                <FileX size={34} color={palette.border} />
              </View>
              <Text style={styles.emptyResultsText}>Henüz form taranmamış</Text>
              <Text style={styles.emptyResultsSubtext}>Yukarıdaki "Tara" butonunu kullanarak başlayın</Text>
            </View>
          ) : (
            completedResults.map((result: any, index: number) => {
              const score = parseFloat(((result.correct / (questionCount || 1)) * 100).toFixed(2));
              return (
                <SwipeableResultItem
                  key={result.id || index}
                  res={result}
                  score={score}
                  onPress={() => handleResultPress(result)}
                  onDelete={() => removeStudentResult(group.id, result.id)}
                  onTransfer={() => openTransferModal(result)}
                  transferEnabled={otherGroups.length > 0}
                />
              );
            })
          )}
        </View>
      </ScrollView>

      <Modal visible={importModalVisible} transparent animationType="fade" onRequestClose={closeImportModal}>
        <View style={styles.overlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Öğrenci Listesi İçe Aktar</Text>
            <Text style={styles.modalDescription}>Excel/CSV dosyasını seçin, önizleyin ve onaylayarak sınıf listesine ekleyin.</Text>

            <TextInput
              style={styles.modalInput}
              value={importPathInput}
              onChangeText={setImportPathInput}
              placeholder="Dosya yolu (.xlsx / .xls / .csv)"
              placeholderTextColor={palette.muted}
            />

            <View style={styles.modalButtonRow}>
              <TouchableOpacity style={styles.modalSecondaryBtn} onPress={() => previewImportFile()}>
                <Text style={styles.modalSecondaryBtnText}>Önizleme Oluştur</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.modalSecondaryBtn} onPress={loadImportCandidates}>
                <Text style={styles.modalSecondaryBtnText}>Klasörü Tara</Text>
              </TouchableOpacity>
            </View>

            {importingRoster ? <ActivityIndicator color={palette.accent} style={{ marginVertical: 10 }} /> : null}

            {importFileCandidates.length > 0 ? (
              <View style={styles.fileListWrap}>
                <Text style={styles.fileListTitle}>Bulunan dosyalar</Text>
                <ScrollView style={styles.fileListScroll} nestedScrollEnabled>
                  {importFileCandidates.map(file => (
                    <TouchableOpacity key={file} style={styles.fileListItem} onPress={() => previewImportFile(file)}>
                      <Text style={styles.fileListItemText} numberOfLines={1}>{file.split(/[\\/]/).pop() || file}</Text>
                    </TouchableOpacity>
                  ))}
                </ScrollView>
              </View>
            ) : null}

            {importPreview.length > 0 ? (
              <View style={styles.previewWrap}>
                <Text style={styles.previewTitle}>Önizleme ({importPreview.length} öğrenci)</Text>
                <ScrollView style={styles.previewScroll} nestedScrollEnabled>
                  {importPreview.slice(0, 80).map((student, index) => (
                    <View key={`${student.student_number}-${index}`} style={styles.previewRow}>
                      <Text style={styles.previewName} numberOfLines={1}>{student.full_name}</Text>
                      <Text style={styles.previewNo}>{student.student_number}</Text>
                    </View>
                  ))}
                </ScrollView>
              </View>
            ) : null}

            <View style={styles.modalButtonRow}>
              <TouchableOpacity style={styles.modalCancelBtn} onPress={closeImportModal}>
                <Text style={styles.modalCancelBtnText}>Kapat</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalPrimaryBtn, importPreview.length === 0 && styles.downloadBtnDisabled]}
                onPress={handleConfirmImport}
                disabled={importPreview.length === 0}
              >
                <Text style={styles.modalPrimaryBtnText}>İçe Aktar</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      <Modal visible={transferModalVisible} transparent animationType="fade" onRequestClose={closeTransferModal}>
        <View style={styles.overlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Sınıfa Aktar</Text>
            <Text style={styles.modalDescription}>
              {selectedTransferResult
                ? `${selectedTransferResult.name || 'Öğrenci'} (${selectedTransferResult.studentNumber || 'No yok'}) için hedef sınıf seçin.`
                : 'Hedef sınıf seçin.'}
            </Text>

            <ScrollView style={styles.transferListScroll}>
              {otherGroups.map(other => {
                const selected = selectedTargetGroupId === other.id;
                return (
                  <TouchableOpacity
                    key={other.id}
                    style={[styles.transferItem, selected && styles.transferItemSelected]}
                    onPress={() => setSelectedTargetGroupId(other.id)}
                  >
                    <Text style={[styles.transferItemTitle, selected && styles.transferItemTitleSelected]}>{getClassName(other)}</Text>
                    <Text style={styles.transferItemMeta}>{buildClassInfoLine(other)}</Text>
                  </TouchableOpacity>
                );
              })}
            </ScrollView>

            <View style={styles.modalButtonRow}>
              <TouchableOpacity style={styles.modalCancelBtn} onPress={closeTransferModal}>
                <Text style={styles.modalCancelBtnText}>Vazgeç</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalPrimaryBtn, !selectedTargetGroupId && styles.downloadBtnDisabled]}
                onPress={() => executeTransfer('skip')}
                disabled={!selectedTargetGroupId}
              >
                <Text style={styles.modalPrimaryBtnText}>Aktar</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.canvas },
  scrollContent: { padding: 16, paddingBottom: 30 },
  centered: { alignItems: 'center', justifyContent: 'center' },
  emptyText: { textAlign: 'center', color: palette.muted, marginTop: 40, fontSize: 16 },

  headerCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 20,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.18,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 6 },
    elevation: 3,
  },
  headerTop: { flexDirection: 'row', alignItems: 'center' },
  headerIconBox: {
    width: 52,
    height: 52,
    borderRadius: radii.md,
    backgroundColor: palette.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 14,
  },
  headerInfo: { flex: 1 },
  headerTitle: { fontSize: 23, fontWeight: '800', color: palette.ink },
  headerSubtitle: { fontSize: 14, color: palette.muted, marginTop: 2 },
  headerMetaDate: { fontSize: 12, color: palette.muted, marginTop: 2 },
  statusRow: { flexDirection: 'row', gap: 8, marginTop: 14, flexWrap: 'wrap' },
  statusBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: palette.mist,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radii.sm,
  },
  statusText: { fontSize: 12, color: palette.muted, fontWeight: '700' },
  statusGreen: { backgroundColor: '#DFF4E5' },
  statusGreenText: { fontSize: 12, color: palette.positive, fontWeight: '700' },
  statusOrange: { backgroundColor: '#FFF4E3' },
  statusOrangeText: { fontSize: 12, color: palette.warning, fontWeight: '700' },

  actionsCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 16,
    marginTop: 14,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.16,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 5 },
    elevation: 3,
  },
  actionRow: { flexDirection: 'row', gap: 10, marginBottom: 10 },
  actionBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: palette.mist,
    paddingVertical: 14,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: palette.border,
    gap: 6,
  },
  actionBtnText: { color: palette.ink, fontWeight: '800', fontSize: 14 },
  scanBtn: { backgroundColor: palette.primary, borderColor: palette.primary },
  scanBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },
  outlineActionBtn: {
    backgroundColor: palette.card,
    borderColor: palette.accent,
  },
  outlineActionBtnText: { color: palette.accent, fontWeight: '800', fontSize: 13 },
  reportBtn: { backgroundColor: palette.accent, borderColor: palette.accent },
  reportBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },
  downloadBtnDisabled: { opacity: 0.5 },

  summaryCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    marginTop: 14,
    padding: 16,
    borderWidth: 1,
    borderColor: palette.border,
  },
  summaryHeader: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 10 },
  summaryTitle: { fontSize: 16, fontWeight: '800', color: palette.ink },
  summaryGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: 8 },
  summaryItem: {
    width: '23%',
    minWidth: 74,
    backgroundColor: palette.mist,
    borderRadius: radii.sm,
    paddingVertical: 10,
    alignItems: 'center',
  },
  summaryValue: { fontSize: 17, fontWeight: '800', color: palette.ink },
  summaryLabel: { fontSize: 11, color: palette.muted, marginTop: 2 },
  summaryMeta: { marginTop: 10, fontSize: 12, color: palette.muted },

  tableCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    marginTop: 14,
    padding: 14,
    borderWidth: 1,
    borderColor: palette.border,
  },
  sectionTitle: { fontSize: 18, fontWeight: '800', color: palette.ink, marginBottom: 10 },
  emptyTableText: { color: palette.muted, fontSize: 13, marginVertical: 6 },
  tableRow: {
    flexDirection: 'row',
    alignItems: 'center',
    borderBottomWidth: 1,
    borderBottomColor: '#DDECE0',
    paddingVertical: 8,
    gap: 8,
  },
  tableStudentCell: { flex: 1.4 },
  tableStudentName: { fontSize: 13, fontWeight: '700', color: palette.ink },
  tableStudentNo: { fontSize: 11, color: palette.muted, marginTop: 2 },
  tableStatusCell: { flex: 0.8, alignItems: 'center' },
  tableStatusText: { fontSize: 11, fontWeight: '700' },
  tableStatusDone: { color: palette.positive },
  tableStatusPending: { color: palette.warning },
  tableScoreCell: { flex: 0.9, alignItems: 'flex-end' },
  tableScoreText: { fontSize: 13, fontWeight: '800', color: palette.ink },
  tableScoreMeta: { fontSize: 10, color: palette.muted, marginTop: 2 },
  tableLimitText: { marginTop: 8, fontSize: 11, color: palette.muted },

  resultsSection: { marginTop: 20 },
  resultCardPendingWrap: {
    flexDirection: 'row',
    alignItems: 'center',
    borderRadius: radii.md,
    padding: 14,
    marginBottom: 10,
  },
  resultCardPending: {
    backgroundColor: '#FFF4E7',
    borderWidth: 1,
    borderColor: '#F0D4AD',
    borderStyle: 'dashed',
  },
  pendingText: { fontSize: 15, fontWeight: '800', color: '#7A4F1A' },
  pendingSubtext: { fontSize: 12, color: '#9A6A29', marginTop: 2 },

  resultCard: {
    backgroundColor: palette.card,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radii.md,
  },
  resultMainArea: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 14,
  },
  resultLeft: { flex: 1, flexDirection: 'row', alignItems: 'center' },
  resultAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  resultAvatarText: { color: palette.white, fontWeight: '800', fontSize: 17 },
  resultInfo: { flex: 1 },
  resultName: { fontSize: 15, fontWeight: '800', color: palette.ink },
  resultNo: { fontSize: 12, color: palette.muted, marginTop: 2 },
  resultRight: { alignItems: 'flex-end', marginRight: 8 },
  resultStats: { flexDirection: 'row', gap: 8 },
  statCorrect: { fontSize: 13, fontWeight: '800', color: palette.positive },
  statWrong: { fontSize: 13, fontWeight: '800', color: palette.negative },
  statBlank: { fontSize: 13, fontWeight: '800', color: palette.muted },
  resultScore: { fontSize: 14, color: palette.primary, fontWeight: '800', marginTop: 4 },

  transferBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 6,
    borderTopWidth: 1,
    borderTopColor: '#DDECE0',
    paddingVertical: 10,
    backgroundColor: '#F4FBF5',
  },
  transferBtnText: { color: palette.accent, fontWeight: '800', fontSize: 12 },

  emptyResultsBox: { alignItems: 'center', paddingVertical: 30 },
  emptyResultsIconWrap: {
    width: 72,
    height: 72,
    borderRadius: radii.md,
    backgroundColor: palette.mist,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  emptyResultsText: { fontSize: 16, fontWeight: '700', color: palette.muted },
  emptyResultsSubtext: { fontSize: 13, color: palette.muted, marginTop: 4 },

  swipeContainer: {
    marginBottom: 10,
    borderRadius: radii.md,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 8,
    shadowOffset: { width: 0, height: 3 },
    elevation: 2,
  },
  deleteBackground: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: palette.negative,
    justifyContent: 'center',
    alignItems: 'flex-end',
    paddingRight: 24,
  },
  deleteText: { color: palette.white, fontWeight: '800', fontSize: 16 },

  overlay: {
    flex: 1,
    backgroundColor: 'rgba(11, 36, 23, 0.55)',
    justifyContent: 'center',
    paddingHorizontal: 18,
  },
  modalCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 18,
    borderWidth: 1,
    borderColor: palette.border,
    maxHeight: '88%',
  },
  modalTitle: { fontSize: 19, fontWeight: '800', color: palette.ink, marginBottom: 6 },
  modalDescription: { fontSize: 12, color: palette.muted, lineHeight: 18, marginBottom: 10 },
  modalInput: {
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radii.sm,
    paddingHorizontal: 12,
    paddingVertical: 10,
    color: palette.ink,
    fontSize: 13,
    backgroundColor: palette.white,
  },
  modalButtonRow: { flexDirection: 'row', gap: 8, marginTop: 10 },
  modalSecondaryBtn: {
    flex: 1,
    backgroundColor: palette.mist,
    borderRadius: radii.sm,
    paddingVertical: 10,
    alignItems: 'center',
  },
  modalSecondaryBtnText: { color: palette.muted, fontWeight: '800', fontSize: 12 },
  modalCancelBtn: {
    flex: 1,
    backgroundColor: palette.mist,
    borderRadius: radii.sm,
    paddingVertical: 11,
    alignItems: 'center',
  },
  modalCancelBtnText: { color: palette.muted, fontWeight: '800', fontSize: 13 },
  modalPrimaryBtn: {
    flex: 1,
    backgroundColor: palette.primary,
    borderRadius: radii.sm,
    paddingVertical: 11,
    alignItems: 'center',
  },
  modalPrimaryBtnText: { color: palette.white, fontWeight: '800', fontSize: 13 },

  fileListWrap: { marginTop: 10 },
  fileListTitle: { fontSize: 12, fontWeight: '700', color: palette.ink, marginBottom: 6 },
  fileListScroll: {
    maxHeight: 120,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radii.sm,
    backgroundColor: palette.white,
  },
  fileListItem: {
    paddingHorizontal: 10,
    paddingVertical: 9,
    borderBottomWidth: 1,
    borderBottomColor: '#E7F0E9',
  },
  fileListItemText: { fontSize: 12, color: palette.ink },

  previewWrap: { marginTop: 10 },
  previewTitle: { fontSize: 12, fontWeight: '700', color: palette.ink, marginBottom: 6 },
  previewScroll: {
    maxHeight: 160,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radii.sm,
    backgroundColor: palette.white,
  },
  previewRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 10,
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: '#E7F0E9',
  },
  previewName: { flex: 1, fontSize: 12, color: palette.ink },
  previewNo: { fontSize: 12, color: palette.muted, fontWeight: '700' },

  transferListScroll: {
    maxHeight: 220,
    marginTop: 4,
    borderWidth: 1,
    borderColor: palette.border,
    borderRadius: radii.sm,
    backgroundColor: palette.white,
  },
  transferItem: {
    paddingHorizontal: 12,
    paddingVertical: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#E7F0E9',
  },
  transferItemSelected: {
    backgroundColor: '#E5F6E9',
  },
  transferItemTitle: { color: palette.ink, fontWeight: '800', fontSize: 13 },
  transferItemTitleSelected: { color: palette.primary },
  transferItemMeta: { color: palette.muted, fontSize: 11, marginTop: 2 },
});
