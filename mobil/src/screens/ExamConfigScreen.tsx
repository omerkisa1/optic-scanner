import React, { useState } from 'react';
import { View, Text, TouchableOpacity, FlatList, StyleSheet } from 'react-native';
import { Save } from 'lucide-react-native';
import { useStore } from '../store/useStore';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { palette, radii } from '../theme/palette';

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
          <Save size={18} color={palette.white} />
          <Text style={styles.saveBtnText}>Cevap Anahtarını Kaydet</Text>
        </TouchableOpacity>
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.canvas },
  listContent: { paddingBottom: 100 },

  headerWrap: { backgroundColor: palette.canvas },
  progressCard: {
    backgroundColor: palette.dark,
    margin: 16,
    marginBottom: 0,
    padding: 16,
    borderRadius: radii.lg,
    shadowColor: '#000',
    shadowOpacity: 0.22,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 8 },
    elevation: 2,
  },
  progressTextRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 },
  progressLabel: { fontSize: 13, color: '#B9C7CA', fontWeight: '700' },
  progressCount: { fontSize: 13, color: '#B9C7CA' },
  progressCountBold: { fontSize: 18, fontWeight: '800', color: '#FFFFFF' },
  progressCountTotal: { fontSize: 13, color: '#9CB0B5' },
  progressBarBg: { height: 7, backgroundColor: '#22373D', borderRadius: radii.pill, overflow: 'hidden' },
  progressBarFill: { height: 7, backgroundColor: palette.accent, borderRadius: radii.pill },

  colHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 20,
    paddingBottom: 8,
  },
  qNoCol: { width: 40 },
  optionCol: { flex: 1, alignItems: 'center' },
  colHeaderText: { fontSize: 15, fontWeight: '800', color: palette.ink },

  questionRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingVertical: 6,
  },
  questionRowEven: { backgroundColor: '#EFE8DA' },
  questionNo: { width: 40, fontSize: 15, fontWeight: '800', color: palette.ink },

  bubble: {
    width: 38,
    height: 38,
    borderRadius: 19,
    borderWidth: 1.8,
    borderColor: palette.border,
    backgroundColor: palette.card,
    alignItems: 'center',
    justifyContent: 'center',
  },
  bubbleSelected: {
    backgroundColor: palette.primary,
    borderColor: palette.primary,
  },
  bubbleSelectedText: {
    color: palette.white,
    fontWeight: '800',
    fontSize: 14,
  },

  footer: {
    position: 'absolute',
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: palette.card,
    paddingHorizontal: 16,
    paddingVertical: 12,
    paddingBottom: 28,
    borderTopWidth: 1.2,
    borderTopColor: palette.border,
  },
  saveBtn: {
    backgroundColor: palette.accent,
    paddingVertical: 14,
    borderRadius: radii.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    shadowColor: palette.accent,
    shadowOpacity: 0.4,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 3 },
    elevation: 5,
  },
  saveBtnText: { color: palette.white, fontWeight: '800', fontSize: 15 },
});
