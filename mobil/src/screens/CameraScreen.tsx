import React, { useRef, useState, useCallback } from 'react';
import {
  View, Text, TouchableOpacity, StyleSheet, Dimensions, ActivityIndicator, Platform,
} from 'react-native';
import { ArrowLeft } from 'lucide-react-native';
import { Camera, useCameraDevice, useCameraPermission } from 'react-native-vision-camera';
import { NativeStackScreenProps } from '@react-navigation/native-stack';
import { RootStackParamList } from '../navigation/AppNavigator';
import { palette, radii } from '../theme/palette';

type Props = NativeStackScreenProps<RootStackParamList, 'Camera'>;

const { width: SCREEN_W, height: SCREEN_H } = Dimensions.get('window');
const FORM_RATIO = 0.71;
const GUIDE_W = Math.min(SCREEN_W * 0.82, (SCREEN_H * 0.78) * FORM_RATIO);
const GUIDE_H = GUIDE_W / FORM_RATIO;
const GUIDE_LEFT = (SCREEN_W - GUIDE_W) / 2;
const GUIDE_TOP = (SCREEN_H - GUIDE_H) / 2 - 30;

const ANCHORS = [
  { id: 'TL', rx: 0.05, ry: 0.05 },
  { id: 'ML', rx: 0.05, ry: 0.50 },
  { id: 'BL', rx: 0.05, ry: 0.95 },
  { id: 'TR', rx: 0.95, ry: 0.05 },
  { id: 'MR', rx: 0.95, ry: 0.50 },
  { id: 'BR', rx: 0.95, ry: 0.95 },
];

const MARKER_SIZE = 22;

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
        <ActivityIndicator color={palette.accent} />
        <Text style={styles.permissionText}>Kamera hazırlanıyor...</Text>
      </View>
    );
  }

  return (
    <View style={styles.container}>
      <Camera
        ref={cameraRef}
        style={StyleSheet.absoluteFill}
        device={device}
        isActive={true}
        photo={true}
      />

      <View style={styles.topBar}>
        <TouchableOpacity style={styles.topBackBtn} onPress={() => navigation.goBack()} activeOpacity={0.9}>
          <ArrowLeft size={16} color={palette.white} />
          <Text style={styles.topBackText}>Geri</Text>
        </TouchableOpacity>
        <View style={styles.groupBadge}>
          <Text style={styles.groupBadgeText}>{groupName}</Text>
        </View>
      </View>

      <View style={styles.maskTop} />
      <View style={[styles.maskSide, { left: 0, top: GUIDE_TOP, width: GUIDE_LEFT, height: GUIDE_H }]} />
      <View style={[styles.maskSide, { left: GUIDE_LEFT + GUIDE_W, top: GUIDE_TOP, width: GUIDE_LEFT + 2, height: GUIDE_H }]} />
      <View style={[styles.maskBottom, { top: GUIDE_TOP + GUIDE_H }]} />

      <View
        style={[
          styles.guideBorder,
          { left: GUIDE_LEFT, top: GUIDE_TOP, width: GUIDE_W, height: GUIDE_H },
        ]}
      />

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

      <View style={styles.hintTop}>
        <Text style={styles.hintText}>Optik formu çerçeveye hizalayın</Text>
        <Text style={styles.hintSubText}>6 siyah kare köşelerdeki ve ortadaki kutularla örtüşmeli</Text>
      </View>

      <View style={styles.captureRow}>
        {capturing ? (
          <ActivityIndicator size="large" color={palette.white} />
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

  maskTop: {
    position: 'absolute', left: 0, right: 0, top: 0,
    height: GUIDE_TOP, backgroundColor: 'rgba(7,15,18,0.62)',
  },
  maskSide: { position: 'absolute', backgroundColor: 'rgba(7,15,18,0.62)' },
  maskBottom: {
    position: 'absolute', left: 0, right: 0, bottom: 0,
    backgroundColor: 'rgba(7,15,18,0.62)',
  },

  guideBorder: {
    position: 'absolute',
    borderWidth: 2.5,
    borderColor: 'rgba(209,243,238,0.92)',
    borderRadius: radii.xs,
  },

  anchorMarker: {
    position: 'absolute',
    width: MARKER_SIZE,
    height: MARKER_SIZE,
    borderWidth: 2.5,
    borderColor: '#D2F3EE',
    backgroundColor: 'transparent',
  },

  topBar: {
    position: 'absolute',
    top: 18,
    left: 16,
    right: 16,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    zIndex: 10,
  },
  topBackBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    backgroundColor: 'rgba(7,15,18,0.65)',
    borderWidth: 1,
    borderColor: 'rgba(210,243,238,0.3)',
    paddingHorizontal: 12,
    paddingVertical: 8,
    borderRadius: radii.pill,
  },
  topBackText: {
    color: palette.white,
    fontWeight: '700',
    fontSize: 13,
  },
  groupBadge: {
    maxWidth: SCREEN_W * 0.55,
    backgroundColor: 'rgba(7,15,18,0.65)',
    borderWidth: 1,
    borderColor: 'rgba(210,243,238,0.3)',
    borderRadius: radii.pill,
    paddingHorizontal: 12,
    paddingVertical: 8,
  },
  groupBadgeText: {
    color: '#D2F3EE',
    fontSize: 12,
    fontWeight: '700',
  },

  hintTop: {
    position: 'absolute',
    top: GUIDE_TOP - 56,
    left: 0,
    right: 0,
    alignItems: 'center',
    paddingHorizontal: 24,
  },
  hintText: {
    color: '#ECFFFB',
    fontSize: 15,
    fontWeight: '800',
    textAlign: 'center',
    textShadowColor: 'rgba(0,0,0,0.8)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },
  hintSubText: {
    color: 'rgba(210,243,238,0.82)',
    fontSize: 12,
    textAlign: 'center',
    marginTop: 4,
    textShadowColor: 'rgba(0,0,0,0.8)',
    textShadowOffset: { width: 0, height: 1 },
    textShadowRadius: 4,
  },

  captureRow: {
    position: 'absolute',
    bottom: 48,
    left: 0,
    right: 0,
    alignItems: 'center',
  },
  captureBtn: {
    width: 76,
    height: 76,
    borderRadius: 38,
    backgroundColor: 'rgba(210,243,238,0.28)',
    borderWidth: 3,
    borderColor: '#D2F3EE',
    alignItems: 'center',
    justifyContent: 'center',
  },
  captureBtnInner: {
    width: 54,
    height: 54,
    borderRadius: 27,
    backgroundColor: '#FFF8EC',
  },

  permissionBox: {
    flex: 1, backgroundColor: palette.dark,
    alignItems: 'center', justifyContent: 'center', gap: 16,
  },
  permissionText: { color: palette.white, fontSize: 16, textAlign: 'center' },
  permissionBtn: {
    backgroundColor: palette.accent,
    paddingHorizontal: 24,
    paddingVertical: 12,
    borderRadius: radii.sm,
  },
  permissionBtnText: { color: palette.white, fontWeight: '800', fontSize: 15 },
});
