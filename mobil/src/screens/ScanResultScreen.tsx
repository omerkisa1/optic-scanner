import React, { useEffect, useState, useRef } from 'react';
import { View, Text, StyleSheet, ActivityIndicator, Image, ScrollView, TouchableOpacity } from 'react-native';
import {
  AlertCircle, ArrowLeft, User, CheckCircle2, XCircle, MinusCircle,
  Info, Save, Camera,
} from 'lucide-react-native';
const CameraPlus = Camera;
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { processForm } from '../api/omrApi';
import { ScanResult } from '../types';
import { useStore } from '../store/useStore';

type Props = NativeStackScreenProps<RootStackParamList, 'ScanResult'>;

const CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E'];

export const ScanResultScreen = ({ route, navigation }: Props) => {
  const { exam, imageUri } = route.params;
  const { addStudentResult } = useStore();
  const [loading, setLoading] = useState(true);
  const [result, setResult] = useState<ScanResult | null>(null);
  const [errorMSG, setErrorMSG] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const isMounted = useRef(true);

  useEffect(() => {
    isMounted.current = true;
    uploadAndProcess();
    return () => { isMounted.current = false; };
  }, []);

  const gradeResult = (res: ScanResult) => {
    let correct = 0, wrong = 0, blank = 0;
    const answers = res.answers || {};
    const answerKey = exam.answerKey || {};
    Object.entries(answers).forEach(([qNo, userAns]) => {
      if (!userAns || userAns === 'Boş') blank++;
      else if (userAns.includes(',')) wrong++;
      else if (userAns === answerKey[qNo]) correct++;
      else wrong++;
    });
    const answeredCount = Object.keys(answers).length;
    if (answeredCount < exam.questionCount) blank += exam.questionCount - answeredCount;
    const score = exam.questionCount > 0
      ? parseFloat(((correct / exam.questionCount) * 100).toFixed(2))
      : 0;
    return { correct, wrong, blank, score };
  };

  const saveResult = (res: ScanResult) => {
    const { correct, wrong, blank, score } = gradeResult(res);
    addStudentResult(exam.id, {
      id: Math.random().toString(36).substr(2, 9),
      name: (res.student_info as any)?.student_name || res.student_info?.name || 'Bilinmeyen',
      studentNumber: res.student_info?.student_number || 'Bilinmiyor',
      correct, wrong, blank, score,
      answers: res.answers || {},
      scannedAt: Date.now(),
    });
    setSaved(true);
  };

  const uploadAndProcess = async () => {
    try {
      setLoading(true);
      setErrorMSG(null);
      const res = await processForm(imageUri, exam.questionCount);
      if (!isMounted.current) {
        if (!(res.error || res.status === 'error')) saveResult(res);
        return;
      }
      if (res.error || res.status === 'error') {
        setErrorMSG(res.error || 'Tarama sırasında hata oluştu.');
      } else {
        setResult(res);
      }
    } catch (err: any) {
      if (isMounted.current) setErrorMSG(err.message || 'Bağlantı hatası.');
    } finally {
      if (isMounted.current) setLoading(false);
    }
  };

  if (loading) {
    return (
      <View style={[styles.container, styles.centered]}>
        <View style={styles.loadingCard}>
          <ActivityIndicator size="large" color="#F4511E" />
          <Text style={styles.loadingTitle}>Optik form okunuyor</Text>
          <Text style={styles.loadingSubtext}>
            Geri dönebilirsiniz. Sonuç hazır olunca otomatik kaydedilir.
          </Text>
          <TouchableOpacity style={styles.backBtn} onPress={() => navigation.goBack()}>
            <ArrowLeft size={16} color="#F4511E" />
            <Text style={styles.backBtnText}>Geri Dön</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  if (errorMSG) {
    return (
      <View style={[styles.container, styles.centered]}>
        <View style={styles.errorCard}>
          <AlertCircle size={48} color="#DC2626" />
          <Text style={styles.errorTitle}>Tarama Başarısız</Text>
          <Text style={styles.errorMsg}>{errorMSG}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={() => navigation.goBack()}>
            <ArrowLeft size={16} color="#fff" />
            <Text style={styles.retryBtnText}>Geri Dön</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }

  if (!result) return null;

  const { correct, wrong, blank, score } = gradeResult(result);
  const answers = result.answers || {};
  const answerKey = exam.answerKey || {};

  const evaluation: any[] = [];
  for (let i = 1; i <= exam.questionCount; i++) {
    const qNo = String(i);
    let userAns = answers[qNo] || '';
    const correctAns = answerKey[qNo] || '-';
    let status: 'correct' | 'wrong' | 'blank' | 'multiple' = 'blank';
    let explanation: string | undefined;

    if (!userAns || userAns === 'Boş') {
      status = 'blank'; userAns = 'Boş';
    } else if (userAns.includes(',')) {
      status = 'multiple';
      const parts = userAns.split(',').map(s => s.trim()).filter(Boolean);
      userAns = parts.join(' ve ');
      explanation = `${parts.join(' ve ')} şıkları birlikte işaretlenmiş — birden fazla şık seçildiği için yanlış sayılmıştır.`;
    } else if (userAns.trim() === correctAns.trim()) {
      status = 'correct';
    } else {
      status = 'wrong';
    }
    evaluation.push({ qNo, userAns, correctAns, status, explanation });
  }

  const statusColors = { correct: '#059669', wrong: '#DC2626', blank: '#9CA3AF', multiple: '#D97706' };
  const StatusIconMap = {
    correct: CheckCircle2,
    wrong: XCircle,
    blank: MinusCircle,
    multiple: AlertCircle,
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={{ paddingBottom: 30 }}>
      <Image source={{ uri: imageUri }} style={styles.previewImage} resizeMode="contain" />

      {/* Student Info */}
      <View style={styles.card}>
        <View style={styles.studentRow}>
          <View style={styles.studentAvatar}>
            <User size={22} color="#F4511E" />
          </View>
          <View style={styles.studentInfo}>
            <Text style={styles.studentName}>
              {(result.student_info as any)?.student_name || result.student_info?.name || 'Okunamadı'}
            </Text>
            <Text style={styles.studentNo}>No: {result.student_info?.student_number || 'Okunamadı'}</Text>
          </View>
        </View>
      </View>

      {/* Score Summary */}
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Sonuç Özeti</Text>
        <View style={styles.statsRow}>
          <View style={[styles.statBox, { backgroundColor: '#ECFDF5' }]}>
            <Text style={[styles.statNum, { color: '#059669' }]}>{correct}</Text>
            <Text style={styles.statLabel}>Doğru</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: '#FEF2F2' }]}>
            <Text style={[styles.statNum, { color: '#DC2626' }]}>{wrong}</Text>
            <Text style={styles.statLabel}>Yanlış</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: '#F3F4F6' }]}>
            <Text style={[styles.statNum, { color: '#6B7280' }]}>{blank}</Text>
            <Text style={styles.statLabel}>Boş</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: '#EFF6FF' }]}>
            <Text style={[styles.statNum, { color: '#2563EB' }]}>{score.toFixed(2)}</Text>
            <Text style={styles.statLabel}>Puan</Text>
          </View>
        </View>
      </View>

      {/* Answer Analysis */}
      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Soru Analizi</Text>
        {evaluation.map((item: any) => (
          <View key={item.qNo} style={styles.questionBlock}>
            <View style={[
              styles.questionHeader,
              {
                backgroundColor: item.status === 'correct' ? '#F0FDF4'
                  : item.status === 'wrong' || item.status === 'multiple' ? '#FEF2F2'
                  : '#F9FAFB',
                borderLeftColor: statusColors[item.status as keyof typeof statusColors],
              },
            ]}>
              <Text style={styles.qNoText}>Soru {item.qNo}</Text>
              {(() => { const SI = StatusIconMap[item.status as keyof typeof StatusIconMap]; return <SI size={18} color={statusColors[item.status as keyof typeof statusColors]} />; })()}
            </View>

            <View style={styles.answerRow}>
              <Text style={styles.answerLabel}>Doğru Cevap</Text>
              <View style={styles.bubblesRow}>
                {CHOICE_LABELS.map(ch => (
                  <View key={ch} style={[
                    styles.bubble,
                    ch === item.correctAns && styles.bubbleCorrect,
                  ]}>
                    <Text style={[styles.bubbleText, ch === item.correctAns && styles.bubbleTextActive]}>{ch}</Text>
                  </View>
                ))}
              </View>
            </View>

            <View style={styles.answerRow}>
              <Text style={styles.answerLabel}>Öğrenci</Text>
              {item.status === 'blank' ? (
                <Text style={styles.blankText}>Boş bırakılmış</Text>
              ) : item.status === 'multiple' ? (
                <View style={styles.bubblesRow}>
                  {item.userAns.split(' ve ').map((ch: string, i: number) => (
                    <View key={i} style={[styles.bubble, styles.bubbleMultiple]}>
                      <Text style={[styles.bubbleText, { color: '#78350F' }]}>{ch}</Text>
                    </View>
                  ))}
                </View>
              ) : (
                <View style={styles.bubblesRow}>
                  {CHOICE_LABELS.map(ch => (
                    <View key={ch} style={[
                      styles.bubble,
                      ch === item.userAns && item.status === 'correct' && styles.bubbleCorrect,
                      ch === item.userAns && item.status === 'wrong' && styles.bubbleWrong,
                    ]}>
                      <Text style={[
                        styles.bubbleText,
                        ch === item.userAns && (item.status === 'correct' || item.status === 'wrong') && styles.bubbleTextActive,
                      ]}>{ch}</Text>
                    </View>
                  ))}
                </View>
              )}
            </View>

            {item.explanation && (
              <View style={styles.explanationRow}>
                <Info size={13} color="#92400E" style={{ marginRight: 6 }} />
                <Text style={styles.explanationText}>{item.explanation}</Text>
              </View>
            )}
          </View>
        ))}
      </View>

      {/* Action Buttons */}
      <View style={styles.actionRow}>
        <TouchableOpacity
          style={styles.saveBtn}
          activeOpacity={0.85}
          onPress={() => {
            if (!saved) saveResult(result);
            navigation.navigate('GroupDetail', { groupId: exam.id } as any);
          }}
        >
          <Save size={16} color="#fff" />
          <Text style={styles.saveBtnText}>Kaydet ve Bitir</Text>
        </TouchableOpacity>

        <TouchableOpacity
          style={styles.moreBtn}
          activeOpacity={0.85}
          onPress={() => {
            if (!saved) saveResult(result);
            navigation.goBack();
          }}
        >
          <CameraPlus size={16} color="#fff" />
          <Text style={styles.moreBtnText}>Başka Tara</Text>
        </TouchableOpacity>
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F7F8FA' },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 20 },

  // Loading
  loadingCard: {
    backgroundColor: '#fff',
    borderRadius: 20,
    padding: 28,
    alignItems: 'center',
    width: '90%',
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 4,
  },
  loadingTitle: { fontSize: 18, fontWeight: '700', color: '#111827', marginTop: 16, marginBottom: 8 },
  loadingSubtext: { fontSize: 13, color: '#6B7280', textAlign: 'center', lineHeight: 20, marginBottom: 20 },
  backBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: 10,
    borderWidth: 1.5,
    borderColor: '#F4511E',
  },
  backBtnText: { color: '#F4511E', fontWeight: '700', fontSize: 14 },

  // Error
  errorCard: {
    backgroundColor: '#fff',
    borderRadius: 20,
    padding: 28,
    alignItems: 'center',
    width: '90%',
    shadowColor: '#000',
    shadowOpacity: 0.08,
    shadowRadius: 12,
    elevation: 4,
  },
  errorTitle: { fontSize: 18, fontWeight: '700', color: '#111827', marginTop: 12, marginBottom: 8 },
  errorMsg: { fontSize: 13, color: '#6B7280', textAlign: 'center', lineHeight: 20, marginBottom: 20 },
  retryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: '#DC2626',
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: 12,
  },
  retryBtnText: { color: '#fff', fontWeight: '700', fontSize: 14 },

  // Preview image
  previewImage: { width: '100%', height: 200, backgroundColor: '#1A1D23' },

  // Cards
  card: {
    backgroundColor: '#fff',
    margin: 16,
    marginBottom: 0,
    padding: 16,
    borderRadius: 14,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 8,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '700',
    color: '#111827',
    marginBottom: 14,
    paddingBottom: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#F3F4F6',
  },

  // Student
  studentRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  studentAvatar: {
    width: 46,
    height: 46,
    borderRadius: 12,
    backgroundColor: '#FEF2ED',
    alignItems: 'center',
    justifyContent: 'center',
  },
  studentInfo: { flex: 1 },
  studentName: { fontSize: 16, fontWeight: '700', color: '#111827' },
  studentNo: { fontSize: 13, color: '#6B7280', marginTop: 2 },

  // Stats
  statsRow: { flexDirection: 'row', gap: 8 },
  statBox: { flex: 1, alignItems: 'center', paddingVertical: 12, borderRadius: 10 },
  statNum: { fontSize: 20, fontWeight: '800' },
  statLabel: { fontSize: 11, color: '#6B7280', marginTop: 3 },

  // Question blocks
  questionBlock: {
    marginBottom: 10,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#E5E7EB',
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
  qNoText: { fontSize: 14, fontWeight: '700', color: '#374151' },
  answerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#F3F4F6',
    gap: 8,
  },
  answerLabel: { width: 90, fontSize: 12, color: '#6B7280', fontWeight: '600' },
  bubblesRow: { flexDirection: 'row', gap: 6 },
  bubble: {
    width: 28,
    height: 28,
    borderRadius: 14,
    borderWidth: 1.5,
    borderColor: '#D1D5DB',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
  },
  bubbleText: { fontSize: 12, color: '#9CA3AF', fontWeight: '700' },
  bubbleTextActive: { color: '#fff' },
  bubbleCorrect: { backgroundColor: '#059669', borderColor: '#059669' },
  bubbleWrong: { backgroundColor: '#DC2626', borderColor: '#DC2626' },
  bubbleMultiple: { backgroundColor: '#FCD34D', borderColor: '#D97706' },
  blankText: { fontSize: 13, color: '#9CA3AF', fontStyle: 'italic' },
  explanationRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#FFFBEB',
    borderTopWidth: 1,
    borderTopColor: '#FEF3C7',
  },
  explanationText: { flex: 1, fontSize: 12, color: '#92400E', lineHeight: 18 },

  // Action buttons
  actionRow: { flexDirection: 'row', margin: 16, gap: 10 },
  saveBtn: {
    flex: 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: '#111827',
    paddingVertical: 14,
    borderRadius: 14,
    shadowColor: '#000',
    shadowOpacity: 0.15,
    shadowRadius: 8,
    elevation: 4,
  },
  saveBtnText: { color: '#fff', fontWeight: '700', fontSize: 14 },
  moreBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: '#F4511E',
    paddingVertical: 14,
    borderRadius: 14,
    shadowColor: '#F4511E',
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 4,
  },
  moreBtnText: { color: '#fff', fontWeight: '700', fontSize: 14 },
});
