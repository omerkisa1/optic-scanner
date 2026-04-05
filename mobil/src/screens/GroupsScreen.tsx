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
  '#C66A44', '#0F766E', '#6B5CA5', '#2E8A68',
  '#A96A2A', '#1E6085', '#8A4A57', '#3D6E8A',
];
const getAvatarColor = (name: string) => {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = name.charCodeAt(i) + ((h << 5) - h);
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
};

export const GroupsScreen = ({ navigation }: Props) => {
  const { groups, addGroup, removeGroup, updateGroupName } = useStore();
  const [showModal, setShowModal] = useState(false);
  const [newGroupName, setNewGroupName] = useState('');
  const [questionCount, setQuestionCount] = useState('20');
  const [editModalVisible, setEditModalVisible] = useState(false);
  const [editGroupId, setEditGroupId] = useState('');
  const [editName, setEditName] = useState('');

  const dashboard = useMemo(() => {
    const scannedCount = groups.reduce((acc, group) => acc + (group.results?.length || 0), 0);
    const configuredCount = groups.filter(group => Object.keys(group.answerKey || {}).length > 0).length;
    return {
      scannedCount,
      configuredCount,
      groupCount: groups.length,
    };
  }, [groups]);

  const handleAddGroup = () => {
    if (!newGroupName.trim()) { Alert.alert('Uyarı', 'Grup adı boş olamaz.'); return; }
    const count = parseInt(questionCount, 10);
    if (isNaN(count) || count < 1 || count > 30) {
      Alert.alert('Hata', 'Soru sayısı 1 ile 30 arasında olmalıdır.'); return;
    }
    addGroup(newGroupName.trim(), count);
    setNewGroupName(''); setQuestionCount('20'); setShowModal(false);
  };

  const handleDelete = (item: any) => {
    Alert.alert('Grubu Sil', `"${item.name}" grubunu silmek istediğinize emin misiniz?`, [
      { text: 'İptal', style: 'cancel' },
      { text: 'Sil', style: 'destructive', onPress: () => removeGroup(item.id) },
    ]);
  };

  const handleEditName = (item: any) => {
    setEditGroupId(item.id); setEditName(item.name); setEditModalVisible(true);
  };

  const handleSaveEditName = () => {
    if (!editName.trim()) { Alert.alert('Uyarı', 'Grup adı boş olamaz.'); return; }
    updateGroupName(editGroupId, editName.trim()); setEditModalVisible(false);
  };

  const renderGroupItem = ({ item }: { item: any }) => {
    const resultCount = item.results?.length || 0;
    const hasAnswerKey = Object.keys(item.answerKey || {}).length > 0;
    const avatarColor = getAvatarColor(item.name);
    const initials = item.name.split(' ').slice(0, 2).map((w: string) => w[0]).join('').toUpperCase();

    return (
      <TouchableOpacity
        style={styles.groupCard}
        onPress={() => navigation.navigate('GroupDetail', { groupId: item.id, groupName: item.name })}
        activeOpacity={0.7}
      >
        <View style={styles.cardTop}>
          <View style={[styles.avatar, { backgroundColor: avatarColor }]}>
            <Text style={styles.avatarText}>{initials}</Text>
          </View>
          <View style={styles.cardInfo}>
            <Text style={styles.groupTitle} numberOfLines={1}>{item.name}</Text>
            <Text style={styles.groupMeta}>
              {item.questionCount} Soru  ·  {new Date(item.createdAt).toLocaleDateString('tr-TR')}
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
      <Text style={styles.dashboardSubtitle}>Tarama gruplarını yönetin ve her sınıfın ilerlemesini tek bakışta görün.</Text>
      <View style={styles.metricRow}>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.groupCount}</Text>
          <Text style={styles.metricLabel}>Grup</Text>
        </View>
        <View style={styles.metricCard}>
          <Text style={styles.metricValue}>{dashboard.scannedCount}</Text>
          <Text style={styles.metricLabel}>Tarama</Text>
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
            <Text style={styles.emptyTitle}>Henüz grubunuz yok</Text>
            <Text style={styles.emptySubtext}>Aşağıdaki butona basarak ilk grubunuzu oluşturun.</Text>
          </View>
        }
        contentContainerStyle={styles.listContainer}
      />

      <TouchableOpacity style={styles.fab} onPress={() => setShowModal(true)} activeOpacity={0.88}>
        <Plus size={20} color={palette.white} strokeWidth={2.6} />
        <Text style={styles.fabText}>Grup Oluştur</Text>
      </TouchableOpacity>

      <Modal visible={showModal} transparent animationType="fade" onRequestClose={() => setShowModal(false)}>
        <View style={styles.overlay}>
          <View style={styles.modal}>
            <Text style={styles.modalTitle}>Yeni Grup Oluştur</Text>
            <Text style={styles.inputLabel}>Grup / Sınıf Adı</Text>
            <TextInput
              style={styles.input} placeholder="Örn: 10-A Matematik"
              placeholderTextColor={palette.muted} value={newGroupName}
              onChangeText={setNewGroupName} autoFocus
            />
            <Text style={styles.inputLabel}>Soru Sayısı</Text>
            <TextInput
              style={styles.input} placeholder="1 – 30" placeholderTextColor={palette.muted}
              value={questionCount} onChangeText={setQuestionCount}
              keyboardType="numeric" maxLength={2}
            />
            <View style={styles.modalBtns}>
              <TouchableOpacity style={styles.cancelBtn} onPress={() => { setShowModal(false); setNewGroupName(''); setQuestionCount('20'); }}>
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
            <Text style={styles.modalTitle}>Grup Adını Düzenle</Text>
            <Text style={styles.inputLabel}>Yeni Grup Adı</Text>
            <TextInput
              style={styles.input} placeholder="Grup adı..."
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
    gap: 10,
  },
  metricCard: {
    flex: 1,
    backgroundColor: '#1A3138',
    borderRadius: radii.md,
    paddingVertical: 12,
    alignItems: 'center',
    borderWidth: 1,
    borderColor: '#2A4952',
  },
  metricValue: {
    color: palette.white,
    fontSize: 18,
    fontWeight: '800',
  },
  metricLabel: {
    color: '#A7B9BE',
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
  cardActions: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  iconBtn: { padding: 6 },

  cardBottom: {
    flexDirection: 'row', gap: 8, marginTop: 12,
    paddingTop: 12, borderTopWidth: 1, borderTopColor: '#ECE4D6', flexWrap: 'wrap',
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
  badgeGreen: { backgroundColor: '#E4F6EE' },
  badgeGreenText: { color: palette.positive },
  badgeOrange: { backgroundColor: '#FFF0DF' },
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
