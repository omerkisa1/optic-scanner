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

type Props = NativeStackScreenProps<RootStackParamList, 'GroupDetail'>;

const SCREEN_WIDTH = Dimensions.get('window').width;

const AVATAR_COLORS = ['#F4511E', '#0EA5E9', '#8B5CF6', '#10B981', '#F59E0B', '#EC4899', '#14B8A6', '#6366F1'];
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
        // Threshold passed, delete
        Animated.timing(swipeAnim, {
          toValue: -SCREEN_WIDTH,
          duration: 200,
          useNativeDriver: true,
        }).start(() => {
          onDelete();
        });
      } else {
        // Reset
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
          <ChevronRight size={18} color="#D1D5DB" />
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

  // Zombi (askıda kalmış) taramaları temizle
  useEffect(() => {
    if (!group) return;
    const now = Date.now();
    // 90 saniyeden fazla süredir pending olanlar zombi sayılır (uygulama kapanmış vs olabilir)
    const zombies = (group.results || []).filter(
      (r: any) => r.pending && (now - (r.scannedAt || 0)) > 90000
    );
    zombies.forEach((zombie: any) => {
      removeStudentResult(group.id, zombie.id);
    });
  }, [group?.id]);

  // Kamera ekranından dönen fotoğrafı al ve işle
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

  // Kamera ekranından dönen fotoğrafı işle
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

      // Grade it
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
        // Eğer ağ bağlantısı vs sebebiyle erken iptal edildiyse listede asılı kalmaması için.
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
    if (res.pending) return; // tıklanamaz
    navigation.navigate('ResultDetail', { groupId: group.id, resultId: res.id });
  };

  const completedResults = (group.results || [])
    .filter((r: any) => !r.pending)
    .sort((a: any, b: any) => (b.scannedAt || 0) - (a.scannedAt || 0));
  const pendingResults = (group.results || []).filter((r: any) => r.pending);
  const hasAnswerKey = Object.keys(group.answerKey || {}).length > 0;

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
      {/* Header Card */}
      <View style={styles.headerCard}>
        <View style={styles.headerTop}>
          <View style={styles.headerIconBox}>
            <BookOpen size={24} color="#F4511E" />
          </View>
          <View style={styles.headerInfo}>
            <Text style={styles.headerTitle}>{group.name}</Text>
            <Text style={styles.headerSubtitle}>{group.questionCount} Soru</Text>
          </View>
        </View>

        <View style={styles.statusRow}>
          {hasAnswerKey ? (
            <View style={[styles.statusBadge, styles.statusGreen]}>
              <CheckCircle size={12} color="#059669" style={{ marginRight: 4 }} />
              <Text style={styles.statusGreenText}>Cevap Anahtarı Hazır</Text>
            </View>
          ) : (
            <View style={[styles.statusBadge, styles.statusOrange]}>
              <AlertCircle size={12} color="#D97706" style={{ marginRight: 4 }} />
              <Text style={styles.statusOrangeText}>Cevap Anahtarı Girilmemiş</Text>
            </View>
          )}
          <View style={styles.statusBadge}>
            <FileText size={12} color="#6B7280" style={{ marginRight: 4 }} />
            <Text style={styles.statusText}>{completedResults.length} Tarama</Text>
          </View>
        </View>
      </View>

      {/* Action Buttons */}
      <View style={styles.actionsCard}>
        <View style={styles.actionRow}>
          <TouchableOpacity
            style={styles.actionBtn}
            onPress={() => navigation.navigate('ExamConfig', { exam: group as any })}
            activeOpacity={0.75}
          >
            <Key size={16} color="#374151" />
            <Text style={styles.actionBtnText}>Cevap Anahtarı</Text>
          </TouchableOpacity>

          <TouchableOpacity
            style={[styles.actionBtn, styles.scanBtn]}
            onPress={handleScan}
            activeOpacity={0.85}
          >
            <Camera size={16} color="#FFFFFF" />
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
            ? <ActivityIndicator size="small" color="#F4511E" />
            : <Download size={16} color="#F4511E" />}
          <Text style={styles.downloadBtnText}>{downloading ? 'İndiriliyor...' : 'Optik Formu İndir'}</Text>
        </TouchableOpacity>

        {completedResults.length > 0 && (
          <TouchableOpacity style={styles.excelBtn} onPress={handleExportExcel} activeOpacity={0.85}>
            <FileSpreadsheet size={16} color="#FFFFFF" />
            <Text style={styles.excelBtnText}>Sonuçları Excel'e Aktar</Text>
          </TouchableOpacity>
        )}
      </View>

      {/* Scan Results */}
      <View style={styles.resultsSection}>
        <Text style={styles.sectionTitle}>Tarama Sonuçları</Text>

        {/* Pending scans */}
        {pendingResults.map((res: any, index: number) => (
          <View key={res.id || `pending-${index}`} style={[styles.resultCard, styles.resultCardPending]}>
            <ActivityIndicator size="small" color="#D97706" style={{ marginRight: 12 }} />
            <View style={styles.resultInfo}>
              <Text style={styles.pendingText}>Taranıyor...</Text>
              <Text style={styles.pendingSubtext}>Optik form okunuyor</Text>
            </View>
          </View>
        ))}

        {/* Completed scans */}
        {completedResults.length === 0 && pendingResults.length === 0 ? (
          <View style={styles.emptyResultsBox}>
            <View style={styles.emptyResultsIconWrap}>
              <FileX size={34} color="#D1D5DB" />
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
  container: { flex: 1, backgroundColor: '#f0f2f5' },
  scrollContent: { padding: 16, paddingBottom: 30 },
  centered: { alignItems: 'center', justifyContent: 'center' },
  emptyText: { textAlign: 'center', color: '#999', marginTop: 40, fontSize: 16 },

  // Header Card
  headerCard: {
    backgroundColor: '#fff',
    borderRadius: 16,
    padding: 20,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 10,
    elevation: 3,
  },
  headerTop: { flexDirection: 'row', alignItems: 'center' },
  headerIconBox: {
    width: 52,
    height: 52,
    borderRadius: 14,
    backgroundColor: '#FEF2ED',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 14,
  },
  headerInfo: { flex: 1 },
  headerTitle: { fontSize: 22, fontWeight: 'bold', color: '#1f2937' },
  headerSubtitle: { fontSize: 14, color: '#9ca3af', marginTop: 2 },
  statusRow: { flexDirection: 'row', gap: 8, marginTop: 14, flexWrap: 'wrap' },
  statusBadge: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#F3F4F6', paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8 },
  statusText: { fontSize: 12, color: '#6B7280', fontWeight: '600' },
  statusGreen: { backgroundColor: '#ECFDF5' },
  statusGreenText: { fontSize: 12, color: '#059669', fontWeight: '600' },
  statusOrange: { backgroundColor: '#FFFBEB' },
  statusOrangeText: { fontSize: 12, color: '#D97706', fontWeight: '600' },

  // Actions Card
  actionsCard: {
    backgroundColor: '#fff',
    borderRadius: 16,
    padding: 16,
    marginTop: 14,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 10,
    elevation: 3,
  },
  actionRow: { flexDirection: 'row', gap: 10, marginBottom: 10 },
  actionBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#f9fafb',
    paddingVertical: 14,
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#e5e7eb',
    gap: 6,
  },
  actionBtnText: { color: '#374151', fontWeight: '700', fontSize: 14 },
  scanBtn: { backgroundColor: '#f4511e', borderColor: '#f4511e' },
  scanBtnText: { color: '#fff', fontWeight: '700', fontSize: 14 },
  downloadBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 13,
    borderRadius: 12,
    borderWidth: 1.5,
    borderColor: '#F4511E',
    gap: 6,
    marginTop: 10,
  },
  downloadBtnText: { color: '#F4511E', fontWeight: '700', fontSize: 14 },
  downloadBtnDisabled: { opacity: 0.5 },
  excelBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 13,
    borderRadius: 12,
    backgroundColor: '#059669',
    marginTop: 10,
    gap: 6,
  },
  excelBtnText: { color: '#fff', fontWeight: '700', fontSize: 14 },

  // Results Section
  resultsSection: { marginTop: 20 },
  sectionTitle: { fontSize: 18, fontWeight: 'bold', color: '#1f2937', marginBottom: 12, paddingHorizontal: 4 },

  // Result Card — marginBottom buraya değil swipeContainer'a taşındı
  resultCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    padding: 14,
    borderRadius: 14,
  },
  resultCardPending: {
    backgroundColor: '#fffbeb',
    borderWidth: 1,
    borderColor: '#fde68a',
    borderStyle: 'dashed',
  },
  pendingDot: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: '#f59e0b',
    marginRight: 12,
  },
  pendingText: { fontSize: 15, fontWeight: 'bold', color: '#92400e' },
  pendingSubtext: { fontSize: 12, color: '#b45309', marginTop: 2 },

  resultLeft: { flex: 1, flexDirection: 'row', alignItems: 'center' },
  resultAvatar: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: '#f4511e',
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  resultAvatarText: { color: '#fff', fontWeight: 'bold', fontSize: 17 },
  resultInfo: { flex: 1 },
  resultName: { fontSize: 15, fontWeight: 'bold', color: '#1f2937' },
  resultNo: { fontSize: 12, color: '#9ca3af', marginTop: 2 },

  resultRight: { alignItems: 'flex-end', marginRight: 8 },
  resultStats: { flexDirection: 'row', gap: 8 },
  statCorrect: { fontSize: 13, fontWeight: 'bold', color: '#16a34a' },
  statWrong: { fontSize: 13, fontWeight: 'bold', color: '#dc2626' },
  statBlank: { fontSize: 13, fontWeight: 'bold', color: '#9ca3af' },
  resultScore: { fontSize: 14, color: '#2563eb', fontWeight: 'bold', marginTop: 4 },


  // Empty results
  emptyResultsBox: { alignItems: 'center', paddingVertical: 30 },
  emptyResultsIconWrap: {
    width: 72,
    height: 72,
    borderRadius: 18,
    backgroundColor: '#F3F4F6',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  emptyResultsText: { fontSize: 16, fontWeight: '600', color: '#6B7280' },
  emptyResultsSubtext: { fontSize: 13, color: '#9CA3AF', marginTop: 4 },

  // Swipeable — shadow burada, resultCard'da değil
  swipeContainer: {
    marginBottom: 10,
    borderRadius: 14,
    overflow: 'hidden',
    shadowColor: '#000',
    shadowOpacity: 0.04,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 1 },
    elevation: 2,
  },
  deleteBackground: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: '#DC2626',
    justifyContent: 'center',
    alignItems: 'flex-end',
    paddingRight: 24,
  },
  deleteText: {
    color: '#fff',
    fontWeight: 'bold',
    fontSize: 16,
  },
});
