import React, { useEffect, useState, useRef } from 'react';
import { View, Text, StyleSheet, ActivityIndicator, Image, ScrollView, TouchableOpacity, Modal, Alert } from 'react-native';
import {
  AlertCircle, ArrowLeft, User, CheckCircle2, XCircle, MinusCircle,
  Info, Save, Camera, ArrowRightLeft,
} from 'lucide-react-native';
const CameraPlus = Camera;
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { processForm } from '../api/omrApi';
import { ScanResult } from '../types';
import { useStore } from '../store/useStore';
import { palette, radii } from '../theme/palette';

type Props = NativeStackScreenProps<RootStackParamList, 'ScanResult'>;

const CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E'];

export const ScanResultScreen = ({ route, navigation }: Props) => {
  const { exam, imageUri } = route.params;
  const { groups, addStudentResult, transferStudentResult } = useStore();
  const [loading, setLoading] = useState(true);
  const [result, setResult] = useState<ScanResult | null>(null);
  const [errorMSG, setErrorMSG] = useState<string | null>(null);
  const [saved, setSaved] = useState(false);
  const [savedResultId, setSavedResultId] = useState('');
  const [transferModalVisible, setTransferModalVisible] = useState(false);
  const [targetGroupId, setTargetGroupId] = useState('');
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
    if (savedResultId) return savedResultId;

    const resultId = Math.random().toString(36).substr(2, 9);
    const { correct, wrong, blank, score } = gradeResult(res);
    addStudentResult(exam.id, {
      id: resultId,
      name: (res.student_info as any)?.student_name || res.student_info?.name || 'Bilinmeyen',
      studentNumber: res.student_info?.student_number || 'Bilinmiyor',
      correct, wrong, blank, score,
      answers: res.answers || {},
      scannedAt: Date.now(),
    });
    setSaved(true);
    setSavedResultId(resultId);
    return resultId;
  };

  const otherGroups = groups.filter(g => g.id !== exam.id);

  const openTransferModal = () => {
    if (!result) return;
    if (otherGroups.length === 0) {
      Alert.alert('Uyarı', 'Aktarım için en az bir farklı sınıf oluşturulmalı.');
      return;
    }

    const ensuredResultId = saved ? savedResultId : saveResult(result);
    if (!ensuredResultId) return;

    const studentNo = String(result.student_info?.student_number || '').replace(/\s+/g, '').trim();
    const suggested = otherGroups.find(group => {
      if (!studentNo) return false;
      const rosterMatch = (group.roster || []).some((student: any) =>
        String(student.student_number || '').replace(/\s+/g, '').trim() === studentNo
      );
      const resultMatch = (group.results || []).some((item: any) =>
        String(item.studentNumber || '').replace(/\s+/g, '').trim() === studentNo
      );
      return rosterMatch || resultMatch;
    });

    setTargetGroupId(suggested?.id || otherGroups[0]?.id || '');
    setTransferModalVisible(true);
  };

  const executeTransfer = (mode: 'skip' | 'replace') => {
    if (!targetGroupId || !savedResultId) return;
    const summary = transferStudentResult(exam.id, targetGroupId, savedResultId, mode);

    if (summary.ok) {
      setTransferModalVisible(false);
      const target = groups.find(g => g.id === targetGroupId);
      navigation.navigate('GroupDetail', {
        groupId: targetGroupId,
        groupName: target?.course_name || target?.name || 'Sınıf',
      });
      return;
    }

    if (summary.reason === 'duplicate-skipped' && mode === 'skip') {
      Alert.alert(
        'Aynı Numara Bulundu',
        'Hedef sınıfta aynı öğrenci numarasına sahip kayıt var. Üzerine yazılsın mı?',
        [
          { text: 'Vazgeç', style: 'cancel' },
          { text: 'Üzerine Yaz', onPress: () => executeTransfer('replace') },
        ]
      );
      return;
    }

    Alert.alert('Hata', 'Aktarım sırasında bir sorun oluştu.');
  };

  const uploadAndProcess = async () => {
    try {
      setLoading(true);
      setErrorMSG(null);
      const res = await processForm(imageUri, exam.question_count || exam.questionCount || 20);
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
          <ActivityIndicator size="large" color={palette.accent} />
          <Text style={styles.loadingTitle}>Optik form okunuyor</Text>
          <Text style={styles.loadingSubtext}>
            Geri dönebilirsiniz. Sonuç hazır olunca otomatik kaydedilir.
          </Text>
          <TouchableOpacity style={styles.backBtn} onPress={() => navigation.goBack()}>
            <ArrowLeft size={16} color={palette.accent} />
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
          <AlertCircle size={48} color={palette.negative} />
          <Text style={styles.errorTitle}>Tarama Başarısız</Text>
          <Text style={styles.errorMsg}>{errorMSG}</Text>
          <TouchableOpacity style={styles.retryBtn} onPress={() => navigation.goBack()}>
            <ArrowLeft size={16} color={palette.white} />
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

  const statusColors = {
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
    <ScrollView style={styles.container} contentContainerStyle={{ paddingBottom: 30 }}>
      <Image source={{ uri: imageUri }} style={styles.previewImage} resizeMode="contain" />

      <View style={styles.card}>
        <View style={styles.studentRow}>
          <View style={styles.studentAvatar}>
            <User size={22} color={palette.primary} />
          </View>
          <View style={styles.studentInfo}>
            <Text style={styles.studentName}>
              {(result.student_info as any)?.student_name || result.student_info?.name || 'Okunamadı'}
            </Text>
            <Text style={styles.studentNo}>No: {result.student_info?.student_number || 'Okunamadı'}</Text>
          </View>
        </View>
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Sonuç Özeti</Text>
        <View style={styles.statsRow}>
          <View style={[styles.statBox, { backgroundColor: '#E4F6EE' }]}>
            <Text style={[styles.statNum, { color: palette.positive }]}>{correct}</Text>
            <Text style={styles.statLabel}>Doğru</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: '#FCE7E7' }]}>
            <Text style={[styles.statNum, { color: palette.negative }]}>{wrong}</Text>
            <Text style={styles.statLabel}>Yanlış</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: palette.mist }]}>
            <Text style={[styles.statNum, { color: palette.muted }]}>{blank}</Text>
            <Text style={styles.statLabel}>Boş</Text>
          </View>
          <View style={[styles.statBox, { backgroundColor: '#DAF4EF' }]}>
            <Text style={[styles.statNum, { color: palette.primary }]}>{score.toFixed(2)}</Text>
            <Text style={styles.statLabel}>Puan</Text>
          </View>
        </View>
      </View>

      <View style={styles.card}>
        <Text style={styles.sectionTitle}>Soru Analizi</Text>
        {evaluation.map((item: any) => (
          <View key={item.qNo} style={styles.questionBlock}>
            <View style={[
              styles.questionHeader,
              {
                backgroundColor: item.status === 'correct' ? '#ECF9F2'
                  : item.status === 'wrong' || item.status === 'multiple' ? '#FFF0ED'
                  : '#F2EBDF',
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
                <Info size={13} color={palette.warning} style={{ marginRight: 6 }} />
                <Text style={styles.explanationText}>{item.explanation}</Text>
              </View>
            )}
          </View>
        ))}
      </View>

      <View style={styles.actionRow}>
        <TouchableOpacity
          style={styles.saveBtn}
          activeOpacity={0.85}
          onPress={() => {
            if (!saved) saveResult(result);
            navigation.navigate('GroupDetail', { groupId: exam.id });
          }}
        >
          <Save size={16} color={palette.white} />
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
          <CameraPlus size={16} color={palette.white} />
          <Text style={styles.moreBtnText}>Başka Tara</Text>
        </TouchableOpacity>

        {otherGroups.length > 0 ? (
          <TouchableOpacity
            style={styles.transferBtn}
            activeOpacity={0.85}
            onPress={openTransferModal}
          >
            <ArrowRightLeft size={16} color={palette.white} />
            <Text style={styles.transferBtnText}>Sınıfa Aktar</Text>
          </TouchableOpacity>
        ) : null}
      </View>

      <Modal visible={transferModalVisible} transparent animationType="fade" onRequestClose={() => setTransferModalVisible(false)}>
        <View style={styles.overlay}>
          <View style={styles.modalCard}>
            <Text style={styles.modalTitle}>Sınıfa Aktar</Text>
            <Text style={styles.modalDescription}>Öğrenci sonucu için hedef sınıf seçin.</Text>

            <ScrollView style={styles.transferList}>
              {otherGroups.map(group => {
                const selected = targetGroupId === group.id;
                return (
                  <TouchableOpacity
                    key={group.id}
                    style={[styles.transferItem, selected && styles.transferItemSelected]}
                    onPress={() => setTargetGroupId(group.id)}
                  >
                    <Text style={[styles.transferItemTitle, selected && styles.transferItemTitleSelected]}>
                      {group.course_name || group.name}
                    </Text>
                    <Text style={styles.transferItemMeta}>
                      {(group.grade_level ? `${group.grade_level}. Sınıf · ` : '')}
                      {(group.section ? `${group.section} Şube · ` : '')}
                      {(group.question_count || group.questionCount || 20)} Soru
                    </Text>
                  </TouchableOpacity>
                );
              })}
            </ScrollView>

            <View style={styles.modalButtons}>
              <TouchableOpacity style={styles.modalCancelBtn} onPress={() => setTransferModalVisible(false)}>
                <Text style={styles.modalCancelBtnText}>Vazgeç</Text>
              </TouchableOpacity>
              <TouchableOpacity
                style={[styles.modalConfirmBtn, !targetGroupId && { opacity: 0.4 }]}
                onPress={() => executeTransfer('skip')}
                disabled={!targetGroupId}
              >
                <Text style={styles.modalConfirmBtnText}>Aktar</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.canvas },
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: 20 },

  loadingCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 28,
    alignItems: 'center',
    width: '90%',
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: '#000',
    shadowOpacity: 0.2,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 8 },
    elevation: 4,
  },
  loadingTitle: { fontSize: 19, fontWeight: '800', color: palette.ink, marginTop: 16, marginBottom: 8 },
  loadingSubtext: { fontSize: 13, color: palette.muted, textAlign: 'center', lineHeight: 20, marginBottom: 20 },
  backBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    paddingVertical: 10,
    paddingHorizontal: 20,
    borderRadius: radii.sm,
    borderWidth: 1.5,
    borderColor: palette.accent,
  },
  backBtnText: { color: palette.accent, fontWeight: '800', fontSize: 14 },

  errorCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 28,
    alignItems: 'center',
    width: '90%',
    borderWidth: 1,
    borderColor: '#EAB8B1',
    shadowColor: '#000',
    shadowOpacity: 0.18,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 8 },
    elevation: 4,
  },
  errorTitle: { fontSize: 18, fontWeight: '800', color: palette.ink, marginTop: 12, marginBottom: 8 },
  errorMsg: { fontSize: 13, color: palette.muted, textAlign: 'center', lineHeight: 20, marginBottom: 20 },
  retryBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: palette.negative,
    paddingVertical: 12,
    paddingHorizontal: 24,
    borderRadius: radii.sm,
  },
  retryBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },

  previewImage: {
    width: '100%',
    height: 220,
    backgroundColor: '#182329',
    borderBottomLeftRadius: radii.lg,
    borderBottomRightRadius: radii.lg,
  },

  card: {
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
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 16,
    fontWeight: '800',
    color: palette.ink,
    marginBottom: 14,
    paddingBottom: 10,
    borderBottomWidth: 1,
    borderBottomColor: '#EDE4D5',
  },

  studentRow: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  studentAvatar: {
    width: 46,
    height: 46,
    borderRadius: radii.md,
    backgroundColor: palette.primarySoft,
    alignItems: 'center',
    justifyContent: 'center',
  },
  studentInfo: { flex: 1 },
  studentName: { fontSize: 17, fontWeight: '800', color: palette.ink },
  studentNo: { fontSize: 13, color: palette.muted, marginTop: 2 },

  statsRow: { flexDirection: 'row', gap: 8 },
  statBox: { flex: 1, alignItems: 'center', paddingVertical: 12, borderRadius: radii.sm },
  statNum: { fontSize: 20, fontWeight: '800' },
  statLabel: { fontSize: 11, color: palette.muted, marginTop: 3 },

  questionBlock: {
    marginBottom: 10,
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
  qNoText: { fontSize: 14, fontWeight: '800', color: palette.ink },
  answerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#EEE5D6',
    gap: 8,
  },
  answerLabel: { width: 90, fontSize: 12, color: palette.muted, fontWeight: '700' },
  bubblesRow: { flexDirection: 'row', gap: 6 },
  bubble: {
    width: 28,
    height: 28,
    borderRadius: 14,
    borderWidth: 1.5,
    borderColor: palette.border,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: palette.card,
  },
  bubbleText: { fontSize: 12, color: palette.muted, fontWeight: '700' },
  bubbleTextActive: { color: palette.white },
  bubbleCorrect: { backgroundColor: palette.positive, borderColor: palette.positive },
  bubbleWrong: { backgroundColor: palette.negative, borderColor: palette.negative },
  bubbleMultiple: { backgroundColor: '#F2C06A', borderColor: palette.warning },
  blankText: { fontSize: 13, color: palette.muted, fontStyle: 'italic' },
  explanationRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 8,
    backgroundColor: '#FFF4E0',
    borderTopWidth: 1,
    borderTopColor: '#EDD6B3',
  },
  explanationText: { flex: 1, fontSize: 12, color: '#7B541F', lineHeight: 18 },

  actionRow: { flexDirection: 'row', margin: 16, gap: 10 },
  saveBtn: {
    flex: 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: palette.primary,
    paddingVertical: 14,
    borderRadius: radii.md,
    shadowColor: '#0B534D',
    shadowOpacity: 0.3,
    shadowRadius: 8,
    elevation: 4,
  },
  saveBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },
  moreBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: palette.accent,
    paddingVertical: 14,
    borderRadius: radii.md,
    shadowColor: palette.accent,
    shadowOpacity: 0.35,
    shadowRadius: 8,
    elevation: 4,
  },
  moreBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },
  transferBtn: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: palette.accent,
    paddingVertical: 14,
    borderRadius: radii.md,
    shadowColor: palette.accent,
    shadowOpacity: 0.35,
    shadowRadius: 8,
    elevation: 4,
  },
  transferBtnText: { color: palette.white, fontWeight: '800', fontSize: 14 },

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
    maxHeight: '85%',
  },
  modalTitle: { fontSize: 19, fontWeight: '800', color: palette.ink, marginBottom: 6 },
  modalDescription: { fontSize: 12, color: palette.muted, marginBottom: 10 },
  transferList: {
    maxHeight: 240,
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
  modalButtons: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 12,
  },
  modalCancelBtn: {
    flex: 1,
    backgroundColor: palette.mist,
    borderRadius: radii.sm,
    paddingVertical: 11,
    alignItems: 'center',
  },
  modalCancelBtnText: { color: palette.muted, fontWeight: '800', fontSize: 13 },
  modalConfirmBtn: {
    flex: 1,
    backgroundColor: palette.primary,
    borderRadius: radii.sm,
    paddingVertical: 11,
    alignItems: 'center',
  },
  modalConfirmBtnText: { color: palette.white, fontWeight: '800', fontSize: 13 },
});
