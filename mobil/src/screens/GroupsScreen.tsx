import React, { useState } from 'react';
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

type Props = NativeStackScreenProps<RootStackParamList, 'Groups'>;

const AVATAR_COLORS = [
  '#F4511E', '#0EA5E9', '#8B5CF6', '#10B981',
  '#F59E0B', '#EC4899', '#14B8A6', '#6366F1',
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
              <Pencil size={17} color="#9CA3AF" />
            </TouchableOpacity>
            <TouchableOpacity
              style={styles.iconBtn}
              onPress={() => handleDelete(item)}
              hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            >
              <Trash2 size={17} color="#9CA3AF" />
            </TouchableOpacity>
          </View>
        </View>

        <View style={styles.cardBottom}>
          <View style={styles.badge}>
            <FileText size={11} color="#6B7280" style={{ marginRight: 4 }} />
            <Text style={styles.badgeText}>{resultCount} Tarama</Text>
          </View>
          {hasAnswerKey ? (
            <View style={[styles.badge, styles.badgeGreen]}>
              <CheckCircle size={11} color="#059669" style={{ marginRight: 4 }} />
              <Text style={[styles.badgeText, styles.badgeGreenText]}>Cevap Anahtarı Hazır</Text>
            </View>
          ) : (
            <View style={[styles.badge, styles.badgeOrange]}>
              <AlertCircle size={11} color="#D97706" style={{ marginRight: 4 }} />
              <Text style={[styles.badgeText, styles.badgeOrangeText]}>Cevap Anahtarı Yok</Text>
            </View>
          )}
        </View>
      </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      <StatusBar backgroundColor="#FFFFFF" barStyle="dark-content" />
      <FlatList
        data={groups}
        keyExtractor={(item) => item.id}
        renderItem={renderGroupItem}
        ListEmptyComponent={
          <View style={styles.emptyContainer}>
            <View style={styles.emptyIconWrap}>
              <FolderOpen size={44} color="#D1D5DB" />
            </View>
            <Text style={styles.emptyTitle}>Henüz grubunuz yok</Text>
            <Text style={styles.emptySubtext}>Aşağıdaki butona basarak ilk grubunuzu oluşturun.</Text>
          </View>
        }
        contentContainerStyle={styles.listContainer}
      />

      <TouchableOpacity style={styles.fab} onPress={() => setShowModal(true)} activeOpacity={0.85}>
        <Plus size={20} color="#fff" strokeWidth={2.5} />
        <Text style={styles.fabText}>Grup Oluştur</Text>
      </TouchableOpacity>

      {/* Yeni Grup Modal */}
      <Modal visible={showModal} transparent animationType="fade" onRequestClose={() => setShowModal(false)}>
        <View style={styles.overlay}>
          <View style={styles.modal}>
            <Text style={styles.modalTitle}>Yeni Grup Oluştur</Text>
            <Text style={styles.inputLabel}>Grup / Sınıf Adı</Text>
            <TextInput
              style={styles.input} placeholder="Örn: 10-A Matematik"
              placeholderTextColor="#9CA3AF" value={newGroupName}
              onChangeText={setNewGroupName} autoFocus
            />
            <Text style={styles.inputLabel}>Soru Sayısı</Text>
            <TextInput
              style={styles.input} placeholder="1 – 30" placeholderTextColor="#9CA3AF"
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

      {/* Düzenleme Modal */}
      <Modal visible={editModalVisible} transparent animationType="fade" onRequestClose={() => setEditModalVisible(false)}>
        <View style={styles.overlay}>
          <View style={styles.modal}>
            <Text style={styles.modalTitle}>Grup Adını Düzenle</Text>
            <Text style={styles.inputLabel}>Yeni Grup Adı</Text>
            <TextInput
              style={styles.input} placeholder="Grup adı..."
              placeholderTextColor="#9CA3AF" value={editName}
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
  container: { flex: 1, backgroundColor: '#F7F8FA' },
  listContainer: { padding: 16, paddingBottom: 100 },

  groupCard: {
    backgroundColor: '#FFFFFF', borderRadius: 16, padding: 16, marginBottom: 12,
    shadowColor: '#000', shadowOpacity: 0.06, shadowRadius: 10,
    shadowOffset: { width: 0, height: 2 }, elevation: 3,
  },
  cardTop: { flexDirection: 'row', alignItems: 'center' },
  avatar: { width: 46, height: 46, borderRadius: 14, alignItems: 'center', justifyContent: 'center', marginRight: 12 },
  avatarText: { color: '#fff', fontWeight: '800', fontSize: 16, letterSpacing: 0.5 },
  cardInfo: { flex: 1 },
  groupTitle: { fontSize: 16, fontWeight: '700', color: '#111827' },
  groupMeta: { fontSize: 13, color: '#9CA3AF', marginTop: 3 },
  cardActions: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  iconBtn: { padding: 6 },

  cardBottom: {
    flexDirection: 'row', gap: 8, marginTop: 12,
    paddingTop: 12, borderTopWidth: 1, borderTopColor: '#F3F4F6', flexWrap: 'wrap',
  },
  badge: { flexDirection: 'row', alignItems: 'center', backgroundColor: '#F3F4F6', paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8 },
  badgeText: { fontSize: 12, color: '#6B7280', fontWeight: '600' },
  badgeGreen: { backgroundColor: '#ECFDF5' },
  badgeGreenText: { color: '#059669' },
  badgeOrange: { backgroundColor: '#FFFBEB' },
  badgeOrangeText: { color: '#D97706' },

  emptyContainer: { alignItems: 'center', paddingTop: 80, paddingHorizontal: 32 },
  emptyIconWrap: { width: 96, height: 96, borderRadius: 24, backgroundColor: '#F3F4F6', alignItems: 'center', justifyContent: 'center', marginBottom: 20 },
  emptyTitle: { fontSize: 20, fontWeight: '700', color: '#374151', marginBottom: 8 },
  emptySubtext: { fontSize: 14, color: '#9CA3AF', textAlign: 'center', lineHeight: 22 },

  fab: {
    position: 'absolute', bottom: 24, left: 20, right: 20,
    backgroundColor: '#F4511E', borderRadius: 16, paddingVertical: 15,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    shadowColor: '#F4511E', shadowOpacity: 0.35, shadowRadius: 14,
    shadowOffset: { width: 0, height: 5 }, elevation: 8,
  },
  fabText: { color: '#fff', fontWeight: '700', fontSize: 16 },

  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'center', paddingHorizontal: 20 },
  modal: { backgroundColor: '#fff', borderRadius: 20, padding: 24 },
  modalTitle: { fontSize: 18, fontWeight: '700', color: '#111827', marginBottom: 20 },
  inputLabel: { fontSize: 13, fontWeight: '600', color: '#374151', marginBottom: 6 },
  input: {
    backgroundColor: '#F9FAFB', borderWidth: 1.5, borderColor: '#E5E7EB',
    borderRadius: 12, paddingHorizontal: 14, paddingVertical: 12,
    fontSize: 15, color: '#111827', marginBottom: 14,
  },
  modalBtns: { flexDirection: 'row', gap: 10, marginTop: 4 },
  cancelBtn: { flex: 1, paddingVertical: 13, borderRadius: 12, alignItems: 'center', backgroundColor: '#F3F4F6' },
  cancelBtnText: { color: '#6B7280', fontWeight: '700', fontSize: 15 },
  confirmBtn: { flex: 1, paddingVertical: 13, borderRadius: 12, alignItems: 'center', backgroundColor: '#F4511E' },
  confirmBtnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
});
