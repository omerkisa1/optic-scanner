import React, { useEffect, useState } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Image,
  Modal,
  Alert,
  Platform,
  PermissionsAndroid,
  ActivityIndicator,
} from 'react-native';
import { CheckCircle2, XCircle, MinusCircle, AlertCircle, Info } from 'lucide-react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import Share from 'react-native-share';
import ReactNativeBlobUtil from 'react-native-blob-util';
import { CameraRoll } from '@react-native-camera-roll/camera-roll';
import { RootStackParamList } from '../navigation/AppNavigator';
import { useStore } from '../store/useStore';
import { palette, radii } from '../theme/palette';

type Props = NativeStackScreenProps<RootStackParamList, 'ResultDetail'>;

const CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E'];

export const ResultDetailScreen = ({ route, navigation }: Props) => {
  const { groupId, resultId } = route.params;
  const { groups } = useStore();
  const [imageExists, setImageExists] = useState(false);
  const [fullscreenOpen, setFullscreenOpen] = useState(false);
  const [sharing, setSharing] = useState(false);
  const [savingToGallery, setSavingToGallery] = useState(false);

  const group = groups.find(g => g.id === groupId);
  const result = group?.results?.find(r => r.id === resultId);
  const answerKey = group?.answerKey || {};

  const toFileUri = (path: string) => (path.startsWith('file://') ? path : `file://${path}`);
  const gradedImageUri = result?.gradedImagePath ? toFileUri(result.gradedImagePath) : '';

  useEffect(() => {
    let mounted = true;

    const checkImage = async () => {
      if (!result?.gradedImagePath) {
        if (mounted) setImageExists(false);
        return;
      }

      const exists = await ReactNativeBlobUtil.fs.exists(result.gradedImagePath).catch(() => false);
      if (mounted) setImageExists(Boolean(exists));
    };

    void checkImage();
    return () => {
      mounted = false;
    };
  }, [result?.gradedImagePath]);

  const ensureGalleryPermission = async () => {
    if (Platform.OS !== 'android') return true;

    const permission = Number(Platform.Version) >= 33
      ? PermissionsAndroid.PERMISSIONS.READ_MEDIA_IMAGES
      : PermissionsAndroid.PERMISSIONS.WRITE_EXTERNAL_STORAGE;

    const granted = await PermissionsAndroid.request(permission);
    return granted === PermissionsAndroid.RESULTS.GRANTED;
  };

  const shareImage = async () => {
    if (!result?.gradedImagePath || !imageExists) {
      Alert.alert('Uyarı', 'Paylaşılacak görsel bulunamadı.');
      return;
    }

    setSharing(true);
    try {
      await Share.open({
        url: gradedImageUri,
        type: 'image/jpeg',
        title: 'Değerlendirilmiş Kağıt',
        message: 'Sınav sonucu',
      });
    } catch (error: any) {
      const message = String(error?.message || '');
      const cancelled = message.includes('User did not share') || message.includes('User cancelled');
      if (!cancelled) {
        Alert.alert('Hata', 'Paylaşım başarısız.');
      }
    } finally {
      setSharing(false);
    }
  };

  const saveToGallery = async () => {
    if (!result?.gradedImagePath || !imageExists) {
      Alert.alert('Uyarı', 'Kaydedilecek görsel bulunamadı.');
      return;
    }

    const granted = await ensureGalleryPermission();
    if (!granted) {
      Alert.alert('İzin Gerekli', 'Galeriye kaydetmek için depolama izni gerekiyor.');
      return;
    }

    setSavingToGallery(true);
    try {
      await CameraRoll.save(gradedImageUri, { type: 'photo' });
      Alert.alert('Başarılı', 'Görsel galeriye kaydedildi.');
    } catch {
      Alert.alert('Hata', 'Galeriye kaydedilemedi.');
    } finally {
      setSavingToGallery(false);
    }
  };

  if (!group || !result) {
    return (
      <View style={[styles.container, styles.centered]}>
        <Text style={styles.emptyText}>Sonuç bulunamadı.</Text>
        <TouchableOpacity style={styles.backBtn} onPress={() => navigation.goBack()}>
          <Text style={styles.backBtnText}>Geri Dön</Text>
        </TouchableOpacity>
      </View>
    );
  }

  const studentAnswers = result.answers || {};
  const questionCount = group.questionCount || 1;
  const hasAnswerData = Object.keys(studentAnswers).length > 0;
  const score = parseFloat(((result.correct / questionCount) * 100).toFixed(2));

  const evaluation: {
    qNo: string;
    userAns: string;
    correctAns: string;
    status: 'correct' | 'wrong' | 'blank' | 'multiple';
    explanation?: string;
  }[] = [];

  if (hasAnswerData) {
    for (let i = 1; i <= questionCount; i++) {
      const qNo = String(i);
      const rawUserAns = studentAnswers[qNo] || '';
      const correctAns = answerKey[qNo] || '-';

      let status: 'correct' | 'wrong' | 'blank' | 'multiple' = 'blank';
      let explanation: string | undefined;
      let displayAns = rawUserAns;

      if (!rawUserAns || rawUserAns === 'Boş') {
        status = 'blank';
        displayAns = 'Boş';
      } else if (rawUserAns.includes(',')) {
        status = 'multiple';
        const parts = rawUserAns.split(',').map(s => s.trim()).filter(Boolean);
        displayAns = parts.join(' ve ');
        explanation = `${parts.join(' ve ')} şıkları birlikte işaretlenmiş. Birden fazla şık işaretlendiği için yanlış sayılmıştır.`;
      } else if (rawUserAns.trim() === correctAns.trim()) {
        status = 'correct';
      } else {
        status = 'wrong';
      }

      evaluation.push({ qNo, userAns: displayAns, correctAns, status, explanation });
    }
  }

  const statusColors: Record<string, string> = {
    correct: palette.positive,
    wrong: palette.negative,
    blank: palette.muted,
    multiple: palette.warning,
  };

  const StatusIconMap = {
    correct: CheckCircle2,
    wrong: XCircle,
    blank: MinusCircle,
    multiple: AlertCircle,
  };

  return (
    <>
      <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
        <View style={styles.infoCard}>
          <Text style={styles.studentName}>{result.name}</Text>
          <Text style={styles.studentNo}>Öğrenci No: {result.studentNumber || 'Belirtilmemiş'}</Text>
          <View style={styles.scoreRow}>
            <View style={[styles.scoreBadge, { backgroundColor: '#E4F6EE' }]}>
              <Text style={[styles.scoreValue, { color: palette.positive }]}>{result.correct}</Text>
              <Text style={styles.scoreLabel}>Doğru</Text>
            </View>
            <View style={[styles.scoreBadge, { backgroundColor: '#FCE7E7' }]}>
              <Text style={[styles.scoreValue, { color: palette.negative }]}>{result.wrong}</Text>
              <Text style={styles.scoreLabel}>Yanlış</Text>
            </View>
            <View style={[styles.scoreBadge, { backgroundColor: palette.mist }]}> 
              <Text style={[styles.scoreValue, { color: palette.muted }]}>{result.blank}</Text>
              <Text style={styles.scoreLabel}>Boş</Text>
            </View>
            <View style={[styles.scoreBadge, { backgroundColor: '#DAF4EF' }]}>
              <Text style={[styles.scoreValue, { color: palette.primary }]}>{score.toFixed(2)}</Text>
              <Text style={styles.scoreLabel}>Puan</Text>
            </View>
          </View>
        </View>

        {result.gradedImagePath ? (
          <View style={styles.imageCard}>
            <Text style={styles.sectionTitle}>Değerlendirilmiş Kağıt</Text>

            {imageExists ? (
              <>
                <TouchableOpacity style={styles.imagePreviewWrap} activeOpacity={0.9} onPress={() => setFullscreenOpen(true)}>
                  <Image source={{ uri: gradedImageUri }} style={styles.gradedImage} resizeMode="contain" />
                </TouchableOpacity>

                <View style={styles.imageButtonRow}>
                  <TouchableOpacity style={styles.imageActionBtn} onPress={shareImage} disabled={sharing}>
                    {sharing ? <ActivityIndicator size="small" color={palette.white} /> : <Text style={styles.imageActionBtnText}>Paylaş</Text>}
                  </TouchableOpacity>

                  <TouchableOpacity style={styles.imageActionSecondaryBtn} onPress={saveToGallery} disabled={savingToGallery}>
                    {savingToGallery ? <ActivityIndicator size="small" color={palette.accent} /> : <Text style={styles.imageActionSecondaryBtnText}>Galeriye Kaydet</Text>}
                  </TouchableOpacity>
                </View>
              </>
            ) : (
              <View style={styles.noImageBox}>
                <Text style={styles.noDataText}>Kaydedilmiş görsel dosyası bulunamadı.</Text>
              </View>
            )}
          </View>
        ) : null}

        <View style={styles.tableCard}>
          <Text style={styles.sectionTitle}>Soru Detayları</Text>

          {!hasAnswerData ? (
            <View style={styles.noDataBox}>
              <Text style={styles.noDataText}>
                Bu sonuç için detaylı cevap verisi bulunmuyor.{"\n"}
                Yeni taranan formların detayları burada görünecektir.
              </Text>
            </View>
          ) : (
            <>
              {evaluation.map(item => (
                <View key={item.qNo} style={styles.questionCard}>
                  <View
                    style={[
                      styles.questionHeader,
                      {
                        backgroundColor:
                          item.status === 'correct' ? '#ECF9F2'
                            : item.status === 'wrong' || item.status === 'multiple' ? '#FFF0ED'
                              : '#F2EBDF',
                        borderLeftColor: statusColors[item.status],
                      },
                    ]}
                  >
                    <Text style={styles.qNoText}>Soru {item.qNo}</Text>
                    {(() => {
                      const SI = StatusIconMap[item.status as keyof typeof StatusIconMap];
                      return <SI size={20} color={statusColors[item.status]} />;
                    })()}
                  </View>

                  <View style={styles.answerRow}>
                    <Text style={styles.answerLabel}>Doğru Cevap:</Text>
                    <View style={styles.bubbleRow}>
                      {CHOICE_LABELS.map(ch => (
                        <View
                          key={ch}
                          style={[
                            styles.choiceBubble,
                            ch === item.correctAns && styles.choiceBubbleCorrect,
                          ]}
                        >
                          <Text
                            style={[
                              styles.choiceBubbleText,
                              ch === item.correctAns && styles.choiceBubbleTextActive,
                            ]}
                          >
                            {ch}
                          </Text>
                        </View>
                      ))}
                    </View>
                  </View>

                  <View style={styles.answerRow}>
                    <Text style={styles.answerLabel}>Öğrenci:</Text>
                    <View style={styles.bubbleRow}>
                      {item.status === 'blank' ? (
                        <Text style={styles.blankText}>Boş bırakılmış</Text>
                      ) : item.status === 'multiple' ? (
                        <View style={styles.multipleRow}>
                          {item.userAns.split(' ve ').map((ch, i) => (
                            <View key={i} style={[styles.choiceBubble, styles.choiceBubbleMultiple]}>
                              <Text style={[styles.choiceBubbleText, styles.choiceBubbleTextMultiple]}>{ch}</Text>
                            </View>
                          ))}
                        </View>
                      ) : (
                        CHOICE_LABELS.map(ch => (
                          <View
                            key={ch}
                            style={[
                              styles.choiceBubble,
                              ch === item.userAns && item.status === 'correct' && styles.choiceBubbleCorrect,
                              ch === item.userAns && item.status === 'wrong' && styles.choiceBubbleWrong,
                            ]}
                          >
                            <Text
                              style={[
                                styles.choiceBubbleText,
                                ch === item.userAns && (item.status === 'correct' || item.status === 'wrong') && styles.choiceBubbleTextActive,
                              ]}
                            >
                              {ch}
                            </Text>
                          </View>
                        ))
                      )}
                    </View>
                  </View>

                  {item.explanation ? (
                    <View style={styles.explanationRow}>
                      <Info size={14} color={palette.warning} style={{ marginRight: 6, marginTop: 1 }} />
                      <Text style={styles.explanationText}>{item.explanation}</Text>
                    </View>
                  ) : null}
                </View>
              ))}
            </>
          )}
        </View>
      </ScrollView>

      <Modal visible={fullscreenOpen} transparent animationType="fade" onRequestClose={() => setFullscreenOpen(false)}>
        <View style={styles.fullscreenOverlay}>
          <TouchableOpacity style={styles.fullscreenCloseBtn} onPress={() => setFullscreenOpen(false)}>
            <Text style={styles.fullscreenCloseText}>Kapat</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.fullscreenImageWrap} activeOpacity={1} onPress={() => setFullscreenOpen(false)}>
            <Image source={{ uri: gradedImageUri }} style={styles.fullscreenImage} resizeMode="contain" />
          </TouchableOpacity>
        </View>
      </Modal>
    </>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.canvas },
  scrollContent: { paddingBottom: 30 },
  centered: { alignItems: 'center', justifyContent: 'center', padding: 20 },
  emptyText: { textAlign: 'center', color: palette.muted, fontSize: 16 },
  backBtn: { backgroundColor: palette.accent, padding: 12, borderRadius: radii.sm, marginTop: 12 },
  backBtnText: { color: palette.white, fontWeight: '800', fontSize: 16 },

  infoCard: {
    backgroundColor: palette.card,
    margin: 16,
    marginBottom: 0,
    padding: 20,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.18,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 6 },
    elevation: 3,
  },
  studentName: { fontSize: 23, fontWeight: '800', color: palette.ink, textAlign: 'center' },
  studentNo: { fontSize: 14, color: palette.muted, textAlign: 'center', marginTop: 4 },
  scoreRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 16, gap: 8 },
  scoreBadge: { flex: 1, alignItems: 'center', paddingVertical: 12, borderRadius: radii.sm },
  scoreValue: { fontSize: 20, fontWeight: '800' },
  scoreLabel: { fontSize: 11, color: palette.muted, marginTop: 2 },

  imageCard: {
    backgroundColor: palette.card,
    margin: 16,
    marginBottom: 0,
    padding: 16,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.16,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 5 },
    elevation: 3,
  },
  imagePreviewWrap: {
    backgroundColor: '#10181B',
    borderRadius: radii.sm,
    overflow: 'hidden',
    borderWidth: 1,
    borderColor: palette.border,
  },
  gradedImage: {
    width: '100%',
    height: 260,
  },
  imageButtonRow: {
    flexDirection: 'row',
    gap: 10,
    marginTop: 12,
  },
  imageActionBtn: {
    flex: 1,
    backgroundColor: palette.accent,
    borderRadius: radii.sm,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
  },
  imageActionBtnText: {
    color: palette.white,
    fontWeight: '800',
    fontSize: 13,
  },
  imageActionSecondaryBtn: {
    flex: 1,
    borderRadius: radii.sm,
    paddingVertical: 12,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: palette.canvas,
    borderWidth: 1,
    borderColor: palette.accent,
  },
  imageActionSecondaryBtnText: {
    color: palette.accent,
    fontWeight: '800',
    fontSize: 13,
  },
  noImageBox: {
    backgroundColor: palette.mist,
    borderRadius: radii.sm,
    padding: 16,
    alignItems: 'center',
  },

  tableCard: {
    backgroundColor: palette.card,
    margin: 16,
    padding: 16,
    borderRadius: radii.lg,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.16,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 5 },
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '800',
    color: palette.ink,
    marginBottom: 12,
  },

  noDataBox: {
    backgroundColor: palette.mist,
    padding: 20,
    borderRadius: radii.sm,
    alignItems: 'center',
  },
  noDataText: { fontSize: 14, color: palette.muted, textAlign: 'center', lineHeight: 22 },

  questionCard: {
    marginBottom: 12,
    borderRadius: radii.sm,
    borderWidth: 1,
    borderColor: palette.border,
    overflow: 'hidden',
  },
  questionHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderLeftWidth: 4,
  },
  qNoText: { fontSize: 15, fontWeight: '800', color: palette.ink },

  answerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#EEE5D6',
  },
  answerLabel: { width: 95, fontSize: 13, color: palette.muted, fontWeight: '700' },
  bubbleRow: { flexDirection: 'row', gap: 6, flex: 1 },

  choiceBubble: {
    width: 30,
    height: 30,
    borderRadius: 15,
    borderWidth: 1.5,
    borderColor: palette.border,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: palette.card,
  },
  choiceBubbleText: { fontSize: 13, color: palette.muted, fontWeight: '700' },
  choiceBubbleTextActive: { color: palette.white },
  choiceBubbleCorrect: { backgroundColor: palette.positive, borderColor: palette.positive },
  choiceBubbleWrong: { backgroundColor: palette.negative, borderColor: palette.negative },
  choiceBubbleMultiple: { backgroundColor: '#F2C06A', borderColor: palette.warning },
  choiceBubbleTextMultiple: { color: '#6C4414' },

  multipleRow: { flexDirection: 'row', gap: 6 },
  blankText: { fontSize: 13, color: palette.muted, fontStyle: 'italic' },

  explanationRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#FFF4E0',
    borderTopWidth: 1,
    borderTopColor: '#EDD6B3',
  },
  explanationText: { flex: 1, fontSize: 12, color: '#7B541F', lineHeight: 18 },

  fullscreenOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.9)',
    justifyContent: 'center',
  },
  fullscreenCloseBtn: {
    position: 'absolute',
    top: 56,
    right: 20,
    zIndex: 2,
    backgroundColor: 'rgba(255,255,255,0.15)',
    borderRadius: radii.pill,
    paddingVertical: 8,
    paddingHorizontal: 14,
  },
  fullscreenCloseText: {
    color: palette.white,
    fontWeight: '800',
    fontSize: 13,
  },
  fullscreenImageWrap: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    paddingHorizontal: 14,
  },
  fullscreenImage: {
    width: '100%',
    height: '78%',
  },
});
