import React from 'react';
import { DefaultTheme, NavigationContainer } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';

import { GroupsScreen } from '../screens/GroupsScreen';
import { GroupDetailScreen } from '../screens/GroupDetailScreen';
import { ExamConfigScreen } from '../screens/ExamConfigScreen';
import { ScanResultScreen } from '../screens/ScanResultScreen';
import { ResultDetailScreen } from '../screens/ResultDetailScreen';
import { CameraScreen } from '../screens/CameraScreen';
import { Exam } from '../types';
import { palette } from '../theme/palette';

export type RootStackParamList = {
  Groups: undefined;
  GroupDetail: { groupId: string; groupName: string; capturedImageUri?: string };
  ExamConfig: { exam: Exam };
  Camera: { groupId: string; groupName: string; questionCount: number };
  ScanResult: { exam: Exam; imageUri: string; examId?: string; questionCount?: number };
  ResultDetail: { groupId: string; resultId: string };
};

const Stack = createNativeStackNavigator<RootStackParamList>();

const appTheme = {
  ...DefaultTheme,
  colors: {
    ...DefaultTheme.colors,
    background: palette.canvas,
    card: palette.dark,
    text: palette.white,
    border: palette.dark,
    primary: palette.accent,
  },
};

export const AppNavigator = () => {
  return (
    <NavigationContainer theme={appTheme}>
      <Stack.Navigator
        initialRouteName="Groups"
        screenOptions={{
          headerStyle: { backgroundColor: palette.dark },
          headerTintColor: palette.white,
          headerTitleStyle: { fontWeight: '800', fontSize: 18 },
          headerShadowVisible: false,
          headerBackTitleVisible: false,
          contentStyle: { backgroundColor: palette.canvas },
          animation: 'slide_from_right',
        }}
      >
        <Stack.Screen
          name="Groups"
          component={GroupsScreen}
          options={{ title: 'Gruplarım' }}
        />
        <Stack.Screen
          name="GroupDetail"
          component={GroupDetailScreen}
          options={({ route }) => ({ title: route.params.groupName })}
        />
        <Stack.Screen
          name="ExamConfig"
          component={ExamConfigScreen}
          options={{ title: 'Cevap Anahtarı' }}
        />
        <Stack.Screen
          name="Camera"
          component={CameraScreen}
          options={{ headerShown: false }}
        />
        <Stack.Screen
          name="ScanResult"
          component={ScanResultScreen}
          options={{ title: 'Tarama Sonucu' }}
        />
        <Stack.Screen
          name="ResultDetail"
          component={ResultDetailScreen}
          options={{ title: 'Sonuç Detayı' }}
        />
      </Stack.Navigator>
    </NavigationContainer>
  );
};
