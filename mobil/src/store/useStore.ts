import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { Group } from '../types';

interface StoreState {
  groups: Group[];

  // Actions
  addGroup: (name: string, questionCount: number) => void;
  removeGroup: (id: string) => void;
  updateGroupName: (groupId: string, name: string) => void;
  updateAnswerKey: (groupId: string, answerKey: Record<string, string>) => void;
  addStudentResult: (groupId: string, result: any) => void;
  updateStudentResult: (groupId: string, resultId: string, result: any) => void;
  removeStudentResult: (groupId: string, resultId: string) => void;
}

const generateId = () => Math.random().toString(36).substr(2, 9);

export const useStore = create<StoreState>()(
  persist(
    (set) => ({
      groups: [],

      addGroup: (name, questionCount) =>
        set((state) => ({
          groups: [
            ...state.groups,
            { id: generateId(), name, questionCount, answerKey: {}, results: [], createdAt: Date.now() },
          ],
        })),

      removeGroup: (id) =>
        set((state) => ({
          groups: state.groups.filter((g) => g.id !== id),
        })),

      updateGroupName: (groupId, name) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId ? { ...g, name } : g
          ),
        })),

      updateAnswerKey: (groupId, answerKey) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId ? { ...g, answerKey } : g
          ),
        })),

      addStudentResult: (groupId, result) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? { ...g, results: [...(g.results || []), result] }
              : g
          ),
        })),

      updateStudentResult: (groupId, resultId, updatedData) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? {
                ...g,
                results: (g.results || []).map((r) =>
                  r.id === resultId ? { ...r, ...updatedData } : r
                ),
              }
              : g
          ),
        })),

      removeStudentResult: (groupId, resultId) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? {
                ...g,
                results: (g.results || []).filter((r) => r.id !== resultId),
              }
              : g
          ),
        })),
    }),
    {
      name: 'omr-storage',
      storage: createJSONStorage(() => AsyncStorage),
    }
  )
);
