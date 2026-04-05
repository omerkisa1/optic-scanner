import React, { useState, useEffect, useRef } from 'react';
import { View, Text, TouchableOpacity, StyleSheet, Alert, Platform, ScrollView, Animated, PanResponder, Dimensions, ActivityIndicator, PermissionsAndroid } from 'react-native';
import {
  BookOpen, Key, Camera, Download, FileSpreadsheet, ChevronRight,
  CheckCircle, AlertCircle, FileText, FileX,
} from 'lucide-react-native';
import { useStore } from '../store/useStore';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import ReactNativeBlobUtil from 'react-native-blob-util';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import { API_BASE_URL, processForm } from '../api/omrApi';
import * as XLSX from 'xlsx';
import { palette, radii } from '../theme/palette';

type Props = NativeStackScreenProps<RootStackParamList, 'GroupDetail'>;

const SCREEN_WIDTH = Dimensions.get('window').width;

const AVATAR_COLORS = ['#C66A44', '#0F766E', '#6B5CA5', '#2E8A68', '#A96A2A', '#1E6085', '#8A4A57', '#3D6E8A'];
const getAvatarColor = (name: string) => {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = name.charCodeAt(i) + ((h << 5) - h);
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
};

const SwipeableResultItem = ({ res, score, onPress, onDelete }: { res: any, score: number, onPress: () => void, onDelete: () => void }) => {
  const [swipeAnim] = useState(new Animated.Value(0));

  const panResponder = PanResponder.create({
    onStartShouldSetPanResponder: () => false,
    onMoveShouldSetPanResponder: (evt, gestureState) => {
      return Math.abs(gestureState.dx) > 20 && Math.abs(gestureState.dx) > Math.abs(gestureState.dy);
    },
    onPanResponderMove: (evt, gestureState) => {
      if (gestureState.dx < 0) {
        swipeAnim.setValue(gestureState.dx);
      }
    },
    onPanResponderRelease: (evt, gestureState) => {
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
      <Animated.View
        style={{ transform: [{ translateX: swipeAnim }] }}
        {...panResponder.panHandlers}
      >
        <TouchableOpacity
          style={styles.resultCard}
          onPress={onPress}
          activeOpacity={0.7}
        >
          <View style={styles.resultLeft}>
            <View style={[styles.resultAvatar, { backgroundColor: getAvatarColor(res.name || '?') }]}>
              <Text style={styles.resultAvatarText}>
                {(res.name || '?')[0].toUpperCase()}
              </Text>
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
      </Animated.View>
    </View>
  );
};

export const GroupDetailScreen = ({ route, navigation }: Props) => {
  const { groupId } = route.params;
  const { groups, addStudentResult, updateStudentResult, removeStudentResult } = useStore();
  const processedUriRef = useRef<string | null>(null);
  const [downloading, setDownloading] = useState(false);

  const group = groups.find(g => g.id === groupId);

  useEffect(() => {
    if (!group) return;
    const now = Date.now();
    const zombies = (group.results || []).filter(
      (r: any) => r.pending && (now - (r.scannedAt || 0)) > 90000
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
  }, [route.params.capturedImageUri]);

  if (!group) {
    return (
      <View style={[styles.container, styles.centered]}>
        <Text style={styles.emptyText}>Grup bulunamadı.</Text>
      </View>
    );
  }

  const handleScan = () => {
    navigation.navigate('Camera', {
      groupId: group.id,
      groupName: group.name,
      questionCount: group.questionCount,
    });
  };

  const handleImageCaptured = (imageUri: string) => {
    const pendingId = Math.random().toString(36).substr(2, 9);
    addStudentResult(group.id, {
      id: pendingId,
      name: 'Okunuyor...',
      studentNumber: '',
      correct: 0, wrong: 0, blank: 0, score: 0,
      answers: {},
      scannedAt: Date.now(),
      pending: true,
    });
    processFormInBackground(imageUri, pendingId);
  };

  const processFormInBackground = async (imageUri: string, pendingId: string) => {
    const controller = new AbortController();
    const timeoutId = setTimeout(() => {
      controller.abort();
      removeStudentResult(group.id, pendingId);
    }, 60_000);

    try {
      const res = await processForm(imageUri, group.questionCount, controller.signal);

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
      const answerKey = group.answerKey || {};

      Object.entries(answers).forEach(([qNo, userAns]) => {
        const correctAns = answerKey[qNo];
        if (!userAns || userAns === 'Boş') {
          blank++;
        } else if (typeof userAns === 'string' && userAns.includes(',')) {
          wrong++;
        } else if (userAns === correctAns) {
          correct++;
        } else {
          wrong++;
        }
      });

      const answeredCount = Object.keys(answers).length;
      if (answeredCount < group.questionCount) {
        blank += group.questionCount - answeredCount;
      }

      const score = parseFloat(((correct / (group.questionCount || 1)) * 100).toFixed(2));

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
    } catch (err: any) {
      clearTimeout(timeoutId);
      if (err.name === 'AbortError' || err.code === 'ERR_CANCELED' || err.message === 'canceled') {
        removeStudentResult(group.id, pendingId);
        return;
      }
      updateStudentResult(group.id, pendingId, {
        name: 'Bağlantı Hatası',
        pending: false,
      });
    }
  };

  const handleDownloadForm = async () => {
    if (downloading) return;
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
      }).fetch('GET', `${API_BASE_URL}/generate_form?question_count=${group.questionCount || 20}`);

      if (downloadResult.info().status === 200) {
        await CameraRoll.save(`file://${downloadResult.path()}`, { type: 'photo' });
        Alert.alert('Başarılı', 'Optik form galerisine (Fotoğraflar) kaydedildi!');
      } else {
        Alert.alert('Hata', 'Form indirilirken bir sorun oluştu.');
      }
    } catch (e: any) {
      Alert.alert('Hata', e.message || 'Resim kaydedilemedi.');
    } finally {
      setDownloading(false);
    }
  };

  const handleExportExcel = async () => {
    try {
      const completedResults = (group.results || []).filter((r: any) => !r.pending);
      if (completedResults.length === 0) {
        Alert.alert('Uyarı', 'Dışa aktarılacak sonuç bulunmuyor.');
        return;
      }

      const data = completedResults.map((res: any, index: number) => ({
        'Sıra': index + 1,
        'Öğrenci Adı': res.name || 'Bilinmeyen',
        'Öğrenci No': res.studentNumber || 'Belirtilmemiş',
        'Doğru Sayısı': res.correct,
        'Yanlış Sayısı': res.wrong,
        'Boş Sayısı': res.blank,
        'Puan (100 üzerinden)': ((res.correct / (group.questionCount || 1)) * 100).toFixed(2),
      }));

      const ws = XLSX.utils.json_to_sheet(data);
      ws['!cols'] = [
        { wch: 6 }, { wch: 25 }, { wch: 15 },
        { wch: 14 }, { wch: 14 }, { wch: 12 }, { wch: 20 },
      ];

      const wb = XLSX.utils.book_new();
      XLSX.utils.book_append_sheet(wb, ws, group.name);

      const wbout = XLSX.write(wb, { type: 'base64', bookType: 'xlsx' });
      const dirs = ReactNativeBlobUtil.fs.dirs;
      const fileName = `${group.name.replace(/[^a-zA-Z0-9ğüşıöçĞÜŞİÖÇ ]/g, '_')}_sonuclari.xlsx`;
      const filePath = `${dirs.DownloadDir}/${fileName}`;

      await ReactNativeBlobUtil.fs.writeFile(filePath, wbout, 'base64');

      if (Platform.OS === 'android') {
        ReactNativeBlobUtil.android.actionViewIntent(
          filePath,
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
        );
      }

      Alert.alert('Başarılı', `Excel dosyası kaydedildi:\n${fileName}`);
    } catch (e: any) {
      Alert.alert('Hata', e.message || 'Excel dosyası oluşturulamadı.');
    }
  };

  const handleResultPress = (res: any) => {
    if (res.pending) return;
    navigation.navigate('ResultDetail', { groupId: group.id, resultId: res.id });
  };

  const completedResults = (group.results || [])
    .filter((r: any) => !r.pending)
    .sort((a: any, b: any) => (b.scannedAt || 0) - (a.scannedAt || 0));
  const pendingResults = (group.results || []).filter((r: any) => r.pending);
  const hasAnswerKey = Object.keys(group.answerKey || {}).length > 0;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
      <View style={styles.headerCard}>
        <View style={styles.headerTop}>
          <View style={styles.headerIconBox}>
            <BookOpen size={24} color={palette.primary} />
          </View>
          <View style={styles.headerInfo}>
            <Text style={styles.headerTitle}>{group.name}</Text>
            <Text style={styles.headerSubtitle}>{group.questionCount} Soru</Text>
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
            <FileText size={12} color={palette.muted} style={{ marginRight: 4 }} />
            <Text style={styles.statusText}>{completedResults.length} Tarama</Text>
          </View>
        </View>
      </View>

      <View style={styles.actionsCard}>
        <View style={styles.actionRow}>
          <TouchableOpacity
            style={styles.actionBtn}
            onPress={() => navigation.navigate('ExamConfig', { exam: group as any })}
            activeOpacity={0.75}
          >
            <Key size={16} color={palette.ink} />
            <Text style={styles.actionBtnText}>Cevap Anahtarı</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.actionBtn, styles.scanBtn]}
            onPress={handleScan}
            activeOpacity={0.85}
          >
            <Camera size={16} color={palette.white} />
            <Text style={styles.scanBtnText}>Tara</Text>
          </TouchableOpacity>
        </View>

        <TouchableOpacity
          style={[styles.downloadBtn, downloading && styles.downloadBtnDisabled]}
          onPress={handleDownloadForm}
          activeOpacity={0.75}
          disabled={downloading}
        >
          {downloading
            ? <ActivityIndicator size="small" color={palette.accent} />
            : <Download size={16} color={palette.accent} />}
          <Text style={styles.downloadBtnText}>{downloading ? 'İndiriliyor...' : 'Optik Formu İndir'}</Text>
        </TouchableOpacity>

        {completedResults.length > 0 && (
          <TouchableOpacity style={styles.excelBtn} onPress={handleExportExcel} activeOpacity={0.85}>
            <FileSpreadsheet size={16} color={palette.white} />
            <Text style={styles.excelBtnText}>Sonuçları Excel'e Aktar</Text>
          </TouchableOpacity>
        )}
      </View>

      <View style={styles.resultsSection}>
        <Text style={styles.sectionTitle}>Tarama Sonuçları</Text>

        {pendingResults.map((res: any, index: number) => (
          <View key={res.id || `pending-${index}`} style={[styles.resultCard, styles.resultCardPending]}>
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
          completedResults.map((res: any, index: number) => {
            const score = parseFloat(((res.correct / (group.questionCount || 1)) * 100).toFixed(2));

            return (
              <SwipeableResultItem
                key={res.id || index}
                res={res}
                score={score}
                onPress={() => handleResultPress(res)}
                onDelete={() => removeStudentResult(group.id, res.id)}
              />
            );
          })
        )}
      </View>
    </ScrollView>
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
  statusGreen: { backgroundColor: '#E4F6EE' },
  statusGreenText: { fontSize: 12, color: palette.positive, fontWeight: '700' },
  statusOrange: { backgroundColor: '#FFF0DF' },
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
  downloadBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 13,
    borderRadius: radii.md,
    borderWidth: 1.5,
    borderColor: palette.accent,
    gap: 6,
    marginTop: 10,
  },
  downloadBtnText: { color: palette.accent, fontWeight: '800', fontSize: 14 },
  downloadBtnDisabled: { opacity: 0.5 },
  excelBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 13,
    borderRadius: radii.md,
    backgroundColor: '#2E8A68',
    marginTop: 10,
    gap: 6,
  },
  excelBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },

  resultsSection: { marginTop: 20 },
  sectionTitle: { fontSize: 19, fontWeight: '800', color: palette.ink, marginBottom: 12, paddingHorizontal: 4 },

  resultCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: palette.card,
    padding: 14,
    borderRadius: radii.md,
    borderWidth: 1,
    borderColor: palette.border,
  },
  resultCardPending: {
    backgroundColor: '#FFF4E7',
    borderWidth: 1,
    borderColor: '#F0D4AD',
    borderStyle: 'dashed',
  },
  pendingDot: { width: 10, height: 10, borderRadius: 5, backgroundColor: palette.warning, marginRight: 12 },
  pendingText: { fontSize: 15, fontWeight: '800', color: '#7A4F1A' },
  pendingSubtext: { fontSize: 12, color: '#9A6A29', marginTop: 2 },

  resultLeft: { flex: 1, flexDirection: 'row', alignItems: 'center' },
  resultAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: palette.accent,
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
});
