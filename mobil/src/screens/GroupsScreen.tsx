import React, { useMemo, useState } from 'react';
import {
  View, Text, TextInput, TouchableOpacity, FlatList,
  StyleSheet, Alert, Modal, StatusBar,
} from 'react-native';
import {
  FolderOpen, Pencil, Trash2, Plus, CheckCircle, AlertCircle, FileText,
} from 'lucide-react-native';
import { useStore } from '../store/useStore';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { palette, radii } from '../theme/palette';

type Props = NativeStackScreenProps<RootStackParamList, 'Groups'>;

const AVATAR_COLORS = [
  '#2F9E44', '#1E7F3B', '#0F6A36', '#3FAE55',
  '#2B8B4B', '#217140', '#46B362', '#2F7552',
];
const GRADE_OPTIONS = ['1', '2', '3', '4'];
const SECTION_OPTIONS = ['Gece A', 'Gece B', 'Gündüz A', 'Gündüz B'];

const getAvatarColor = (name: string) => {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = name.charCodeAt(i) + ((h << 5) - h);
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
};

const getClassName = (item: any) => item.course_name || item.name || 'İsimsiz Sınıf';
const getQuestionCount = (item: any) => item.question_count || item.questionCount || 20;
const getCreatedAt = (item: any) => item.created_at || item.createdAt || Date.now();
const getAnswerKey = (item: any) => item.answer_key || item.answerKey || {};

const buildClassMeta = (item: any) => {
  const parts: string[] = [];
  if (item.grade_level) parts.push(`${item.grade_level}. Sınıf`);
  if (item.section) parts.push(`${item.section} Şubesi`);
  parts.push(`${getQuestionCount(item)} Soru`);
  return parts.join('  ·  ');
};

export const GroupsScreen = ({ navigation }: Props) => {
  const { groups, addGroup, addClass, removeGroup, updateGroupName } = useStore();
  const [showModal, setShowModal] = useState(false);
  const [courseName, setCourseName] = useState('');
  const [questionCount, setQuestionCount] = useState('20');
  const [gradeLevel, setGradeLevel] = useState('');
  const [section, setSection] = useState('');
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editGroupId, setEditGroupId] = useState('');
  const [editName, setEditName] = useState('');

  const dashboard = useMemo(() => {
    const scannedCount = groups.reduce((acc, group) => {
      const completed = (group.results || []).filter((result: any) => !result.pending).length;
      return acc + completed;
    }, 0);
    const configuredCount = groups.filter(group => Object.keys(getAnswerKey(group)).length > 0).length;
    const rosterCount = groups.reduce((acc, group) => acc + (group.roster?.length || 0), 0);
    return {
      scannedCount,
      configuredCount,
      rosterCount,
      classCount: groups.length,
    };
  }, [groups]);

  const resetCreateForm = () => {
    setCourseName('');
    setQuestionCount('20');
    setGradeLevel('');
    setSection('');
  };

  const handleAddGroup = () => {
    if (!courseName.trim()) {
      Alert.alert('Uyarı', 'Sınıf adı boş olamaz.');
      return;
    }
    const count = parseInt(questionCount, 10);
    if (isNaN(count) || count < 1 || count > 200) {
      Alert.alert('Hata', 'Soru sayısı 1 ile 200 arasında olmalıdır.');
      return;
    }

    if (addClass) {
      addClass({
        course_name: courseName.trim(),
        question_count: count,
        grade_level: gradeLevel || undefined,
        section: section || undefined,
      });
    } else {
      addGroup(courseName.trim(), count);
    }

    resetCreateForm();
    setShowModal(false);
  };

  const handleDelete = (item: any) => {
    Alert.alert('Sınıfı Sil', `"${getClassName(item)}" sınıfını silmek istediğinize emin misiniz?`, [
      { text: 'İptal', style: 'cancel' },
      { text: 'Sil', style: 'destructive', onPress: () => removeGroup(item.id) },
    ]);
  };

  const handleEditName = (item: any) => {
    setEditGroupId(item.id);
    setEditName(getClassName(item));
    setEditModalVisible(true);
  };

  const handleSaveEditName = () => {
    if (!editName.trim()) {
      Alert.alert('Uyarı', 'Sınıf adı boş olamaz.');
      return;
    }
    updateGroupName(editGroupId, editName.trim());
    setEditModalVisible(false);
  };

  const renderGroupItem = ({ item }: { item: any }) => {
    const className = getClassName(item);
    const resultCount = (item.results || []).filter((r: any) => !r.pending).length;
    const hasAnswerKey = Object.keys(getAnswerKey(item)).length > 0;
    const avatarColor = getAvatarColor(className);
    const initials = className
      .split(' ')
      .filter(Boolean)
      .slice(0, 2)
      .map((w: string) => w[0])
      .join('')
      .toUpperCase();

    return (
      <TouchableOpacity
        style={styles.groupCard}
        onPress={() => navigation.navigate('GroupDetail', { groupId: item.id, groupName: className })}
        activeOpacity={0.7}
      >
        <View style={styles.cardTop}>
          <View style={[styles.avatar, { backgroundColor: avatarColor }]}>
            <Text style={styles.avatarText}>{initials}</Text>
          </View>
          <View style={styles.cardInfo}>
            <Text style={styles.groupTitle} numberOfLines={1}>{className}</Text>
            <Text style={styles.groupMeta}>
              {buildClassMeta(item)}
            </Text>
            <Text style={styles.groupDate}>
              Oluşturulma: {new Date(getCreatedAt(item)).toLocaleDateString('tr-TR')}
            </Text>
          </View>
          <View style={styles.cardActions}>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={() => handleEditName(item)}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <Pencil size={17} color={palette.muted} />
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={() => handleDelete(item)}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <Trash2 size={17} color={palette.muted} />
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.cardBottom}>
          <View style={styles.badge}>
            <FileText size={11} color={palette.muted} style={{ marginRight: 4 }} />
            <Text style={styles.badgeText}>{resultCount} Tarama</Text>
          </View>
          <View style={styles.badge}>
            <Text style={styles.badgeText}>{item.roster?.length || 0} Öğrenci</Text>
          </View>
          {hasAnswerKey ? (
            <View style={[styles.badge, styles.badgeGreen]}>
              <CheckCircle size={11} color={palette.positive} style={{ marginRight: 4 }} />
              <Text style={[styles.badgeText, styles.badgeGreenText]}>Cevap Anahtarı Hazır</Text>
            </View>
          ) : (
            <View style={[styles.badge, styles.badgeOrange]}>
              <AlertCircle size={11} color={palette.warning} style={{ marginRight: 4 }} />
              <Text style={[styles.badgeText, styles.badgeOrangeText]}>Cevap Anahtarı Yok</Text>
            </View>
          )}
        </View>
      </TouchableOpacity>
    );
  };

  const renderDashboard = () => (
    <View style={styles.dashboardCard}>
      <Text style={styles.dashboardTitle}>Sınıf Panosu</Text>
      <Text style={styles.dashboardSubtitle}>Sınıfları, öğrenci listelerini ve tarama ilerlemesini tek ekrandan yönetin.</Text>
      <View style={styles.metricRow}>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.classCount}</Text>
          <Text style={styles.metricLabel}>Sınıf</Text>
        </View>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.scannedCount}</Text>
          <Text style={styles.metricLabel}>Tarama</Text>
        </View>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.rosterCount}</Text>
          <Text style={styles.metricLabel}>Öğrenci</Text>
        </View>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.configuredCount}</Text>
          <Text style={styles.metricLabel}>Anahtar</Text>
        </View>
      </View>
    </View>
  );

  return (
    <View style={styles.container}>
      <StatusBar backgroundColor={palette.dark} barStyle="light-content" />
      <FlatList
        data={groups}
        keyExtractor={(item) => item.id}
        renderItem={renderGroupItem}
        ListHeaderComponent={renderDashboard}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <View style={styles.emptyIconWrap}>
              <FolderOpen size={44} color={palette.border} />
            </View>
            <Text style={styles.emptyTitle}>Henüz sınıfınız yok</Text>
            <Text style={styles.emptySubtext}>Aşağıdaki butona basarak ilk sınıfınızı oluşturun.</Text>
          </View>
        }
        contentContainerStyle={styles.listContainer}
      />

      <TouchableOpacity style={styles.fab} onPress={() => setShowModal(true)} activeOpacity={0.88}>
        <Plus size={20} color={palette.white} strokeWidth={2.6} />
          <Text style={styles.fabText}>Sınıf Oluştur</Text>
      </TouchableOpacity>

      <Modal visible={showModal} transparent animationType="fade" onRequestClose={() => setShowModal(false)}>
        <View style={styles.overlay}>
          <View style={styles.modal}>
              <Text style={styles.modalTitle}>Yeni Sınıf Oluştur</Text>
              <Text style={styles.inputLabel}>Ders / Sınıf Adı</Text>
            <TextInput
                style={styles.input} placeholder="Örn: Matematik"
                placeholderTextColor={palette.muted} value={courseName}
                onChangeText={setCourseName} autoFocus
            />
            <Text style={styles.inputLabel}>Soru Sayısı</Text>
            <TextInput
                style={styles.input} placeholder="1 – 200" placeholderTextColor={palette.muted}
              value={questionCount} onChangeText={setQuestionCount}
                keyboardType="numeric" maxLength={3}
            />

              <Text style={styles.inputLabel}>Sınıf Düzeyi (Opsiyonel)</Text>
              <View style={styles.optionRow}>
                {GRADE_OPTIONS.map(opt => (
                  <TouchableOpacity
                    key={opt}
                    style={[styles.optionChip, gradeLevel === opt && styles.optionChipActive]}
                    onPress={() => setGradeLevel(prev => prev === opt ? '' : opt)}
                    activeOpacity={0.8}
                  >
                    <Text style={[styles.optionChipText, gradeLevel === opt && styles.optionChipTextActive]}>{opt}</Text>
                  </TouchableOpacity>
                ))}
              </View>

              <Text style={styles.inputLabel}>Şube (Opsiyonel)</Text>
              <View style={styles.optionRow}>
                {SECTION_OPTIONS.map(opt => (
                  <TouchableOpacity
                    key={opt}
                    style={[styles.optionChip, styles.sectionChip, section === opt && styles.optionChipActive]}
                    onPress={() => setSection(prev => prev === opt ? '' : opt)}
                    activeOpacity={0.8}
                  >
                    <Text style={[styles.optionChipText, section === opt && styles.optionChipTextActive]}>{opt}</Text>
                  </TouchableOpacity>
                ))}
              </View>

            <View style={styles.modalBtns}>
                <TouchableOpacity
                  style={styles.cancelBtn}
                  onPress={() => {
                    setShowModal(false);
                    resetCreateForm();
                  }}
                >
                <Text style={styles.cancelBtnText}>İptal</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.confirmBtn} onPress={handleAddGroup}>
                <Text style={styles.confirmBtnText}>Oluştur</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>

      <Modal visible={editModalVisible} transparent animationType="fade" onRequestClose={() => setEditModalVisible(false)}>
        <View style={styles.overlay}>
          <View style={styles.modal}>
            <Text style={styles.modalTitle}>Sınıf Adını Düzenle</Text>
            <Text style={styles.inputLabel}>Yeni Sınıf Adı</Text>
            <TextInput
              style={styles.input} placeholder="Sınıf adı..."
              placeholderTextColor={palette.muted} value={editName}
              onChangeText={setEditName} autoFocus
            />
            <View style={styles.modalBtns}>
              <TouchableOpacity style={styles.cancelBtn} onPress={() => setEditModalVisible(false)}>
                <Text style={styles.cancelBtnText}>İptal</Text>
              </TouchableOpacity>
              <TouchableOpacity style={styles.confirmBtn} onPress={handleSaveEditName}>
                <Text style={styles.confirmBtnText}>Kaydet</Text>
              </TouchableOpacity>
            </View>
          </View>
        </View>
      </Modal>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: palette.canvas },
  listContainer: { padding: 16, paddingBottom: 120 },

  dashboardCard: {
    backgroundColor: palette.dark,
    borderRadius: radii.lg,
    paddingHorizontal: 18,
    paddingVertical: 20,
    marginBottom: 14,
    shadowColor: '#000',
    shadowOpacity: 0.22,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 10 },
    elevation: 8,
  },
  dashboardTitle: {
    fontSize: 24,
    fontWeight: '800',
    color: palette.white,
    letterSpacing: 0.2,
  },
  dashboardSubtitle: {
    marginTop: 6,
    color: '#CAD7D9',
    fontSize: 13,
    lineHeight: 19,
  },
  metricRow: {
    marginTop: 16,
    flexDirection: 'row',
    gap: 8,
    flexWrap: 'wrap',
  },
  metricCard: {
    width: '23%',
    minWidth: 74,
    backgroundColor: '#1A4B2E',
    borderRadius: radii.md,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#2A6A42',
  },
  metricValue: {
    color: palette.white,
    fontSize: 18,
    fontWeight: '800',
  },
  metricLabel: {
    color: '#B9DDC8',
    fontSize: 12,
    marginTop: 2,
  },

  groupCard: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 16,
    marginBottom: 12,
    borderWidth: 1,
    borderColor: palette.border,
    shadowColor: palette.shadow,
    shadowOpacity: 0.2,
    shadowRadius: 10,
    shadowOffset: { width: 0, height: 5 },
    elevation: 3,
  },
  cardTop: { flexDirection: 'row', alignItems: 'center' },
  avatar: {
    width: 48,
    height: 48,
    borderRadius: 14,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: 12,
  },
  avatarText: { color: palette.white, fontWeight: '800', fontSize: 16, letterSpacing: 0.5 },
  cardInfo: { flex: 1 },
  groupTitle: { fontSize: 17, fontWeight: '800', color: palette.ink },
  groupMeta: { fontSize: 13, color: palette.muted, marginTop: 3 },
  groupDate: { fontSize: 11, color: palette.muted, marginTop: 3 },
  cardActions: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  iconBtn: { padding: 6 },

  cardBottom: {
    flexDirection: 'row', gap: 8, marginTop: 12,
    paddingTop: 12, borderTopWidth: 1, borderTopColor: '#DAEAD8', flexWrap: 'wrap',
  },
  badge: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: palette.mist,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: radii.sm,
  },
  badgeText: { fontSize: 12, color: palette.muted, fontWeight: '700' },
  badgeGreen: { backgroundColor: '#DFF4E5' },
  badgeGreenText: { color: palette.positive },
  badgeOrange: { backgroundColor: '#FFF4E3' },
  badgeOrangeText: { color: palette.warning },

  emptyContainer: { alignItems: 'center', paddingTop: 80, paddingHorizontal: 32 },
  emptyIconWrap: {
    width: 96,
    height: 96,
    borderRadius: 24,
    backgroundColor: palette.mist,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 20,
  },
  emptyTitle: { fontSize: 20, fontWeight: '800', color: palette.ink, marginBottom: 8 },
  emptySubtext: { fontSize: 14, color: palette.muted, textAlign: 'center', lineHeight: 22 },

  fab: {
    position: 'absolute', bottom: 24, left: 20, right: 20,
    backgroundColor: palette.accent, borderRadius: radii.md, paddingVertical: 15,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    shadowColor: palette.accent, shadowOpacity: 0.42, shadowRadius: 16,
    shadowOffset: { width: 0, height: 5 }, elevation: 8,
  },
  fabText: { color: palette.white, fontWeight: '800', fontSize: 16 },

  overlay: { flex: 1, backgroundColor: 'rgba(14, 24, 31, 0.56)', justifyContent: 'center', paddingHorizontal: 20 },
  modal: {
    backgroundColor: palette.card,
    borderRadius: radii.lg,
    padding: 24,
    borderWidth: 1,
    borderColor: palette.border,
  },
  modalTitle: { fontSize: 20, fontWeight: '800', color: palette.ink, marginBottom: 20 },
  inputLabel: { fontSize: 13, fontWeight: '700', color: palette.ink, marginBottom: 6 },
  input: {
    backgroundColor: palette.white, borderWidth: 1.5, borderColor: palette.border,
    borderRadius: radii.sm, paddingHorizontal: 14, paddingVertical: 12,
    fontSize: 15, color: palette.ink, marginBottom: 14,
  },
  optionRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 14,
  },
  optionChip: {
    minWidth: 42,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: 10,
    paddingVertical: 8,
    borderRadius: radii.pill,
    borderWidth: 1,
    borderColor: palette.border,
    backgroundColor: palette.mist,
  },
  sectionChip: {
    minWidth: 92,
  },
  optionChipActive: {
    backgroundColor: palette.primary,
    borderColor: palette.primary,
  },
  optionChipText: {
    color: palette.muted,
    fontWeight: '700',
    fontSize: 12,
  },
  optionChipTextActive: {
    color: palette.white,
  },
  modalBtns: { flexDirection: 'row', gap: 10, marginTop: 4 },
  cancelBtn: {
    flex: 1,
    paddingVertical: 13,
    borderRadius: radii.sm,
    alignItems: 'center',
    backgroundColor: palette.mist,
  },
  cancelBtnText: { color: palette.muted, fontWeight: '800', fontSize: 15 },
  confirmBtn: {
    flex: 1,
    paddingVertical: 13,
    borderRadius: radii.sm,
    alignItems: 'center',
    backgroundColor: palette.primary,
  },
  confirmBtnText: { color: palette.white, fontWeight: '800', fontSize: 15 },
});
