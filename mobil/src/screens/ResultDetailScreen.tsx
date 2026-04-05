import React from 'react';
import { View, Text, StyleSheet, ScrollView, TouchableOpacity } from 'react-native';
import { CheckCircle2, XCircle, MinusCircle, AlertCircle, Info } from 'lucide-react-native';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { useStore } from '../store/useStore';

type Props = NativeStackScreenProps<RootStackParamList, 'ResultDetail'>;

const CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E'];

export const ResultDetailScreen = ({ route, navigation }: Props) => {
  const { groupId, resultId } = route.params;
  const { groups } = useStore();

  const group = groups.find(g => g.id === groupId);
  const result = group?.results?.find(r => r.id === resultId);
  const answerKey = group?.answerKey || {};

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

  // Score: 100 üzerinden, doğru sayısına göre
  const score = parseFloat(((result.correct / questionCount) * 100).toFixed(2));

  // Build evaluation list
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
        // Multiple answers marked (e.g., "B, A" or "A,C")
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
    correct: '#16a34a',
    wrong: '#dc2626',
    blank: '#9ca3af',
    multiple: '#d97706',
  };

  const StatusIconMap = {
    correct: CheckCircle2,
    wrong: XCircle,
    blank: MinusCircle,
    multiple: AlertCircle,
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.scrollContent}>
      {/* Student Info Card */}
      <View style={styles.infoCard}>
        <Text style={styles.studentName}>{result.name}</Text>
        <Text style={styles.studentNo}>Öğrenci No: {result.studentNumber || 'Belirtilmemiş'}</Text>
        <View style={styles.scoreRow}>
          <View style={[styles.scoreBadge, { backgroundColor: '#e8f5e9' }]}>
            <Text style={[styles.scoreValue, { color: '#16a34a' }]}>{result.correct}</Text>
            <Text style={styles.scoreLabel}>Doğru</Text>
          </View>
          <View style={[styles.scoreBadge, { backgroundColor: '#ffebee' }]}>
            <Text style={[styles.scoreValue, { color: '#dc2626' }]}>{result.wrong}</Text>
            <Text style={styles.scoreLabel}>Yanlış</Text>
          </View>
          <View style={[styles.scoreBadge, { backgroundColor: '#f3f4f6' }]}>
            <Text style={[styles.scoreValue, { color: '#9ca3af' }]}>{result.blank}</Text>
            <Text style={styles.scoreLabel}>Boş</Text>
          </View>
          <View style={[styles.scoreBadge, { backgroundColor: '#eff6ff' }]}>
            <Text style={[styles.scoreValue, { color: '#2563eb' }]}>{score.toFixed(2)}</Text>
            <Text style={styles.scoreLabel}>Puan</Text>
          </View>
        </View>
      </View>

      {/* Answer Detail Table */}
      <View style={styles.tableCard}>
        <Text style={styles.sectionTitle}>Soru Detayları</Text>

        {!hasAnswerData ? (
          <View style={styles.noDataBox}>
            <Text style={styles.noDataText}>
              Bu sonuç için detaylı cevap verisi bulunmuyor.{'\n'}
              Yeni taranan formların detayları burada görünecektir.
            </Text>
          </View>
        ) : (
          <>
            {/* Table Rows */}
            {evaluation.map(item => (
              <View key={item.qNo} style={styles.questionCard}>
                {/* Question number and status */}
                <View style={[
                  styles.questionHeader,
                  {
                    backgroundColor:
                      item.status === 'correct' ? '#f0fdf4'
                      : item.status === 'wrong' || item.status === 'multiple' ? '#fef2f2'
                      : '#f9fafb',
                    borderLeftColor: statusColors[item.status],
                  },
                ]}>
                  <Text style={styles.qNoText}>Soru {item.qNo}</Text>
                  {(() => { const SI = StatusIconMap[item.status as keyof typeof StatusIconMap]; return <SI size={20} color={statusColors[item.status]} />; })()}
                </View>

                {/* Answer Key Row */}
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

                {/* Student Answer Row */}
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

                {/* Explanation for multiple marks */}
                {item.explanation && (
                  <View style={styles.explanationRow}>
                    <Info size={14} color="#92400E" style={{ marginRight: 6, marginTop: 1 }} />
                    <Text style={styles.explanationText}>{item.explanation}</Text>
                  </View>
                )}
              </View>
            ))}
          </>
        )}
      </View>
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F7F8FA' },
  scrollContent: { paddingBottom: 30 },
  centered: { alignItems: 'center', justifyContent: 'center', padding: 20 },
  emptyText: { textAlign: 'center', color: '#999', fontSize: 16 },
  backBtn: { backgroundColor: '#f4511e', padding: 12, borderRadius: 8, marginTop: 12 },
  backBtnText: { color: '#fff', fontWeight: 'bold', fontSize: 16 },

  // Info Card
  infoCard: {
    backgroundColor: '#fff',
    margin: 16,
    marginBottom: 0,
    padding: 20,
    borderRadius: 16,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  studentName: { fontSize: 22, fontWeight: 'bold', color: '#1f2937', textAlign: 'center' },
  studentNo: { fontSize: 14, color: '#6b7280', textAlign: 'center', marginTop: 4 },
  scoreRow: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 16, gap: 8 },
  scoreBadge: { flex: 1, alignItems: 'center', paddingVertical: 12, borderRadius: 10 },
  scoreValue: { fontSize: 20, fontWeight: 'bold' },
  scoreLabel: { fontSize: 11, color: '#6b7280', marginTop: 2 },

  // Table Card
  tableCard: {
    backgroundColor: '#fff',
    margin: 16,
    padding: 16,
    borderRadius: 16,
    shadowColor: '#000',
    shadowOpacity: 0.06,
    shadowRadius: 8,
    elevation: 3,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#1f2937',
    marginBottom: 12,
  },

  // No data
  noDataBox: {
    backgroundColor: '#f9fafb',
    padding: 20,
    borderRadius: 10,
    alignItems: 'center',
  },
  noDataText: { fontSize: 14, color: '#6b7280', textAlign: 'center', lineHeight: 22 },

  // Question cards
  questionCard: {
    marginBottom: 12,
    borderRadius: 10,
    borderWidth: 1,
    borderColor: '#e5e7eb',
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
  qNoText: { fontSize: 15, fontWeight: 'bold', color: '#374151' },

  // Answer rows
  answerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderTopWidth: 1,
    borderTopColor: '#f3f4f6',
  },
  answerLabel: { width: 95, fontSize: 13, color: '#6b7280', fontWeight: '600' },
  bubbleRow: { flexDirection: 'row', gap: 6, flex: 1 },

  // Choice bubbles
  choiceBubble: {
    width: 30,
    height: 30,
    borderRadius: 15,
    borderWidth: 1.5,
    borderColor: '#d1d5db',
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#fff',
  },
  choiceBubbleText: { fontSize: 13, color: '#9ca3af', fontWeight: '600' },
  choiceBubbleTextActive: { color: '#fff' },
  choiceBubbleCorrect: { backgroundColor: '#16a34a', borderColor: '#16a34a' },
  choiceBubbleWrong: { backgroundColor: '#dc2626', borderColor: '#dc2626' },
  choiceBubbleMultiple: { backgroundColor: '#fbbf24', borderColor: '#d97706' },
  choiceBubbleTextMultiple: { color: '#78350f' },

  multipleRow: { flexDirection: 'row', gap: 6 },
  blankText: { fontSize: 13, color: '#9ca3af', fontStyle: 'italic' },

  // Explanation
  explanationRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    paddingHorizontal: 12,
    paddingVertical: 10,
    backgroundColor: '#FFFBEB',
    borderTopWidth: 1,
    borderTopColor: '#FEF3C7',
  },
  explanationText: { flex: 1, fontSize: 12, color: '#92400E', lineHeight: 18 },
});
