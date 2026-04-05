import React, { useRef, useState, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet, Dimensions, ActivityIndicator, Platform,
} from 'react-native';
import { Camera, useCameraDevice, useCameraPermission } from 'react-native-vision-camera';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';

type Props = NativeStackScreenProps<RootStackParamList, 'Camera'>;

const { width: SCREEN_W, height: SCREEN_H } = Dimensions.get('window');
const FORM_RATIO = 0.71; // 1000 / 1400

// Form kılavuzunun ekrandaki boyutunu hesapla
const GUIDE_W = Math.min(SCREEN_W * 0.82, (SCREEN_H * 0.78) * FORM_RATIO);
const GUIDE_H = GUIDE_W / FORM_RATIO;
const GUIDE_LEFT = (SCREEN_W - GUIDE_W) / 2;
const GUIDE_TOP = (SCREEN_H - GUIDE_H) / 2 - 30; // hafif yukarı kaydır

// Anchor pozisyonları (schema ile birebir)
const ANCHORS = [
  { id: 'TL', rx: 0.05, ry: 0.05 },
  { id: 'ML', rx: 0.05, ry: 0.50 },
  { id: 'BL', rx: 0.05, ry: 0.95 },
  { id: 'TR', rx: 0.95, ry: 0.05 },
  { id: 'MR', rx: 0.95, ry: 0.50 },
  { id: 'BR', rx: 0.95, ry: 0.95 },
];

const MARKER_SIZE = 22; // piksel cinsinden kılavuz kare boyutu

export const CameraScreen = ({ route, navigation }: Props) => {
  const { groupId, groupName } = route.params;
  const device = useCameraDevice('back');
  const { hasPermission, requestPermission } = useCameraPermission();
  const cameraRef = useRef<Camera>(null);
  const [capturing, setCapturing] = useState(false);

  const handleCapture = useCallback(async () => {
    if (!cameraRef.current || capturing) return;
    try {
      setCapturing(true);
      const photo = await cameraRef.current.takePhoto({ flash: 'off' });
      const uri = Platform.OS === 'ios' ? photo.path : `file://${photo.path}`;
      navigation.navigate('GroupDetail', {
        groupId,
        groupName,
        capturedImageUri: uri,
      });
    } catch {
      setCapturing(false);
    }
  }, [capturing]);

  if (!hasPermission) {
    return (
      <View style={styles.permissionBox}>
        <Text style={styles.permissionText}>Kamera izni gerekiyor</Text>
        <TouchableOpacity style={styles.permissionBtn} onPress={requestPermission}>
          <Text style={styles.permissionBtnText}>İzin Ver</Text>
        </TouchableOpacity>
      </View>
    );
  }

  if (!device) {
    return (
      <View style={styles.permissionBox}>
        <ActivityIndicator color="#F4511E" />
        <Text style={styles.permissionText}>Kamera hazırlanıyor...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      {/* Kamera görüntüsü */}
      <Camera
        ref={cameraRef}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        photo={true}
      />

      {/* Karartma maskesi — kılavuz dikdörtgeni dışı */}
      <View style={styles.maskTop} />
      <View style={[styles.maskSide, { left: 0, top: GUIDE_TOP, width: GUIDE_LEFT, height: GUIDE_H }]} />
      <View style={[styles.maskSide, { left: GUIDE_LEFT + GUIDE_W, top: GUIDE_TOP, width: GUIDE_LEFT + 2, height: GUIDE_H }]} />
      <View style={[styles.maskBottom, { top: GUIDE_TOP + GUIDE_H }]} />

      {/* Kılavuz dikdörtgeni (form sınırı) */}
      <View
        style={[
          styles.guideBorder,
          { left: GUIDE_LEFT, top: GUIDE_TOP, width: GUIDE_W, height: GUIDE_H },
        ]}
      />

      {/* Anchor kare göstergeleri */}
      {ANCHORS.map(a => {
        const ax = GUIDE_LEFT + a.rx * GUIDE_W - MARKER_SIZE / 2;
        const ay = GUIDE_TOP + a.ry * GUIDE_H - MARKER_SIZE / 2;
        return (
          <View
            key={a.id}
            style={[styles.anchorMarker, { left: ax, top: ay }]}
          />
        );
      })}

      {/* Üst metin */}
      <View style={styles.hintTop}>
        <Text style={styles.hintText}>Optik formu çerçeveye hizalayın</Text>
        <Text style={styles.hintSubText}>6 siyah kare köşelerdeki ve ortadaki kutularla örtüşmeli</Text>
      </View>

      {/* Çekim butonu */}
      <View style={styles.captureRow}>
        {capturing ? (
          <ActivityIndicator size="large" color="#fff" />
        ) : (
          <TouchableOpacity style={styles.captureBtn} onPress={handleCapture} activeOpacity={0.8}>
            <View style={styles.captureBtnInner} />
          </TouchableOpacity>
        )}
      </View>
    </View>
  );
};

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#000' },

  // Mask panels
  maskTop: {
    position: 'absolute', left: 0, right: 0, top: 0,
    height: GUIDE_TOP, backgroundColor: 'rgba(0,0,0,0.55)',
  },
  maskSide: { position: 'absolute', backgroundColor: 'rgba(0,0,0,0.55)' },
  maskBottom: {
    position: 'absolute', left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(0,0,0,0.55)',
  },

  // Guide border
  guideBorder: {
    position: 'absolute',
    borderWidth: 2,
    borderColor: 'rgba(255,255,255,0.7)',
    borderRadius: 2,
  },

  // Anchor marker (hollow square, matches form's black squares)
  anchorMarker: {
    position: 'absolute',
    width: MARKER_SIZE,
    height: MARKER_SIZE,
    borderWidth: 2.5,
    borderColor: '#FFFFFF',
    backgroundColor: 'transparent',
  },

  // Hint overlay
  hintTop: {
    position: 'absolute',
    top: GUIDE_TOP - 56,
    left: 0,
    right: 0,
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  hintText: {
    color: '#FFFFFF',
    fontSize: 15,
    fontWeight: '700',
    textAlign: 'center',
    textShadowColor: 'rgba(0,0,0,0.8)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },
  hintSubText: {
    color: 'rgba(255,255,255,0.75)',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 4,
    textShadowColor: 'rgba(0,0,0,0.8)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },

  // Capture button
  captureRow: {
    position: 'absolute',
    bottom: 48,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  captureBtn: {
    width: 72,
    height: 72,
    borderRadius: 36,
    backgroundColor: 'rgba(255,255,255,0.25)',
    borderWidth: 3,
    borderColor: '#FFFFFF',
    alignItems: 'center',
    justifyContent: 'center',
  },
  captureBtnInner: {
    width: 52,
    height: 52,
    borderRadius: 26,
    backgroundColor: '#FFFFFF',
  },

  // Permission screen
  permissionBox: {
    flex: 1, backgroundColor: '#000',
    alignItems: 'center', justifyContent: 'center', gap: 16,
  },
  permissionText: { color: '#fff', fontSize: 16, textAlign: 'center' },
  permissionBtn: {
    backgroundColor: '#F4511E', paddingHorizontal: 24, paddingVertical: 12, borderRadius: 10,
  },
  permissionBtnText: { color: '#fff', fontWeight: '700', fontSize: 15 },
});
