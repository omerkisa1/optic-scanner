import React, { useState } from 'react';
import { View, Text, TouchableOpacity, FlatList, StyleSheet } from 'react-native';
import { Save } from 'lucide-react-native';
import { useStore } from '../store/useStore';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';

type Props = NativeStackScreenProps<RootStackParamList, 'ExamConfig'>;

const CHOICE_LABELS = ['A', 'B', 'C', 'D', 'E'];

export const ExamConfigScreen = ({ route, navigation }: Props) => {
  const { exam } = route.params;
  const { updateAnswerKey } = useStore();

  const [localAnswers, setLocalAnswers] = useState<Record<string, string>>(exam.answerKey || {});

  const questions = Array.from({ length: exam.questionCount || 20 }, (_, i) => ({
    q_no: i + 1,
    options: CHOICE_LABELS,
  }));

  const answeredCount = Object.values(localAnswers).filter(v => v && v !== '').length;
  const totalCount = exam.questionCount || 20;

  const handleSelectOption = (qNo: string, val: string) => {
    setLocalAnswers(prev => ({
      ...prev,
      [qNo]: prev[qNo] === val ? '' : val,
    }));
  };

  const handleSave = () => {
    updateAnswerKey(exam.id, localAnswers);
    navigation.goBack();
  };

  const renderHeader = () => (
    <View style={styles.headerWrap}>
      {/* Progress Bar */}
      <View style={styles.progressCard}>
        <View style={styles.progressTextRow}>
          <Text style={styles.progressLabel}>Yanıtlanan Sorular</Text>
          <Text style={styles.progressCount}>
            <Text style={styles.progressCountBold}>{answeredCount}</Text>
            <Text style={styles.progressCountTotal}> / {totalCount}</Text>
          </Text>
        </View>
        <View style={styles.progressBarBg}>
          <View style={[styles.progressBarFill, { width: `${(answeredCount / totalCount) * 100}%` as any }]} />
        </View>
      </View>

      {/* Column Headers */}
      <View style={styles.colHeaderRow}>
        <View style={styles.qNoCol} />
        {CHOICE_LABELS.map(val => (
          <View key={val} style={styles.optionCol}>
            <Text style={styles.colHeaderText}>{val}</Text>
          </View>
        ))}
      </View>
    </View>
  );

  const renderQuestionRow = ({ item, index }: { item: any; index: number }) => {
    const qNoStr = item.q_no.toString();
    const selectedVal = localAnswers[qNoStr];
    const isEven = index % 2 === 0;

    return (
      <View style={[styles.questionRow, isEven && styles.questionRowEven]}>
        <View style={styles.qNoCol}>
          <Text style={styles.questionNo}>{item.q_no}</Text>
        </View>
        {item.options.map((val: string) => {
          const isSelected = selectedVal === val;
          return (
            <View key={val} style={styles.optionCol}>
              <TouchableOpacity
                style={[styles.bubble, isSelected && styles.bubbleSelected]}
                onPress={() => handleSelectOption(qNoStr, val)}
                activeOpacity={0.7}
              >
                {isSelected ? (
                  <Text style={styles.bubbleSelectedText}>{val}</Text>
                ) : null}
              </TouchableOpacity>
            </View>
          );
        })}
      </View>
    );
  };

  return (
    <View style={styles.container}>
      <FlatList
        data={questions}
        keyExtractor={item => item.q_no.toString()}
        renderItem={renderQuestionRow}
        ListHeaderComponent={renderHeader}
        contentContainerStyle={styles.listContent}
      />
      <View style={styles.footer}>
        <TouchableOpacity style={styles.saveBtn} onPress={handleSave} activeOpacity={0.85}>
          <Save size={18} color="#fff" />
          <Text style={styles.saveBtnText}>Cevap Anahtarını Kaydet</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F7F8FA' },
  listContent: { paddingBottom: 100 },

  // Header
  headerWrap: { backgroundColor: '#F7F8FA' },
  progressCard: {
    backgroundColor: '#FFFFFF',
    margin: 16,
    marginBottom: 0,
    padding: 16,
    borderRadius: 14,
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 8,
    elevation: 2,
  },
  progressTextRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  progressLabel: { fontSize: 13, color: '#6B7280', fontWeight: '600' },
  progressCount: { fontSize: 13, color: '#6B7280' },
  progressCountBold: { fontSize: 16, fontWeight: '800', color: '#F4511E' },
  progressCountTotal: { fontSize: 13, color: '#9CA3AF' },
  progressBarBg: { height: 6, backgroundColor: '#F3F4F6', borderRadius: 3, overflow: 'hidden' },
  progressBarFill: { height: 6, backgroundColor: '#F4511E', borderRadius: 3 },

  // Column header
  colHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 20,
    paddingBottom: 8,
  },
  qNoCol: { width: 40 },
  optionCol: { flex: 1, alignItems: 'center' },
  colHeaderText: { fontSize: 15, fontWeight: '700', color: '#374151' },

  // Question row
  questionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 6,
  },
  questionRowEven: { backgroundColor: '#FAFAFA' },
  questionNo: { width: 40, fontSize: 14, fontWeight: '700', color: '#374151' },

  // Bubble
  bubble: {
    width: 38,
    height: 38,
    borderRadius: 19,
    borderWidth: 2,
    borderColor: '#E5E7EB',
    backgroundColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  bubbleSelected: {
    backgroundColor: '#F4511E',
    borderColor: '#F4511E',
  },
  bubbleSelectedText: {
    color: '#FFFFFF',
    fontWeight: '800',
    fontSize: 14,
  },

  // Footer
  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: '#FFFFFF',
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    borderTopWidth: 1,
    borderTopColor: '#F3F4F6',
  },
  saveBtn: {
    backgroundColor: '#F4511E',
    paddingVertical: 14,
    borderRadius: 14,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    shadowColor: '#F4511E',
    shadowOpacity: 0.3,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 3 },
    elevation: 5,
  },
  saveBtnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
});
