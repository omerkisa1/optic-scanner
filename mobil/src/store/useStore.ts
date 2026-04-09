import { create } from 'zustand';
import { persist, createJSONStorage } from 'zustand/middleware';
import AsyncStorage from '@react-native-async-storage/async-storage';
import { deleteResultImage } from '../api/omrApi';
import { Group, CreateClassInput, ClassRosterStudent, StudentResult } from '../types';

type MergeMode = 'skip' | 'replace';

interface RosterMergeSummary {
  added: number;
  skipped: number;
  replaced: number;
}

interface TransferResultSummary {
  ok: boolean;
  reason?: 'source-not-found' | 'target-not-found' | 'result-not-found' | 'duplicate-skipped';
}

interface StoreState {
  groups: Group[];

  addGroup: (name: string, questionCount: number) => string;
  addClass: (input: CreateClassInput) => string;
  removeGroup: (id: string) => void;
  updateGroupName: (groupId: string, name: string) => void;
  updateGroupMeta: (groupId: string, payload: Partial<CreateClassInput>) => void;
  updateAnswerKey: (groupId: string, answerKey: Record<string, string>) => void;
  addStudentResult: (groupId: string, result: Partial<StudentResult>) => void;
  updateStudentResult: (groupId: string, resultId: string, result: Partial<StudentResult>) => void;
  removeStudentResult: (groupId: string, resultId: string) => void;
  setRosterStudents: (groupId: string, students: ClassRosterStudent[]) => void;
  addRosterStudents: (groupId: string, students: ClassRosterStudent[], mode?: MergeMode) => RosterMergeSummary;
  transferStudentResult: (sourceGroupId: string, targetGroupId: string, resultId: string, mode?: MergeMode) => TransferResultSummary;
}

const generateId = () => Math.random().toString(36).slice(2, 11);
const normalizeTrim = (value: unknown) => String(value ?? '').trim();
const normalizeOptional = (value: unknown) => {
  const cleaned = normalizeTrim(value);
  return cleaned.length > 0 ? cleaned : undefined;
};
const normalizeQuestionCount = (value: unknown) => {
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 1) return 20;
  return Math.floor(parsed);
};
const normalizeStudentNumber = (value: unknown) => normalizeTrim(value).replace(/\s+/g, '');

const normalizeAnswerKey = (answerKey: unknown): Record<string, string> => {
  if (!answerKey || typeof answerKey !== 'object') return {};
  const normalized: Record<string, string> = {};
  Object.entries(answerKey as Record<string, unknown>).forEach(([qNo, answer]) => {
    const cleanQNo = normalizeTrim(qNo);
    const cleanAnswer = normalizeTrim(answer).toUpperCase();
    if (cleanQNo && cleanAnswer) normalized[cleanQNo] = cleanAnswer;
  });
  return normalized;
};

const normalizeStudentResult = (input: Partial<StudentResult>): StudentResult => {
  const answers = input.answers && typeof input.answers === 'object' ? input.answers : {};
  const gradedImagePath = normalizeTrim(input.gradedImagePath);
  return {
    id: normalizeTrim(input.id) || generateId(),
    name: normalizeTrim(input.name) || 'Bilinmeyen',
    studentNumber: normalizeTrim(input.studentNumber),
    correct: Number(input.correct ?? 0) || 0,
    wrong: Number(input.wrong ?? 0) || 0,
    blank: Number(input.blank ?? 0) || 0,
    score: Number(input.score ?? 0) || 0,
    answers: answers as Record<string, string>,
    gradedImagePath: gradedImagePath || undefined,
    scannedAt: Number(input.scannedAt ?? Date.now()) || Date.now(),
    pending: Boolean(input.pending),
  };
};

const normalizeRosterStudent = (input: Partial<ClassRosterStudent>): ClassRosterStudent => {
  return {
    id: normalizeTrim(input.id) || generateId(),
    full_name: normalizeTrim(input.full_name),
    student_number: normalizeTrim(input.student_number),
    grade_level: normalizeOptional(input.grade_level),
    section: normalizeOptional(input.section),
    created_at: Number(input.created_at ?? Date.now()) || Date.now(),
  };
};

const withLegacyFields = (group: Group): Group => ({
  ...group,
  name: group.course_name,
  questionCount: group.question_count,
  answerKey: group.answer_key,
  createdAt: group.created_at,
});

const normalizeGroup = (group: Partial<Group>): Group => {
  const courseName = normalizeTrim(group.course_name ?? group.name) || 'Yeni Sınıf';
  const questionCount = normalizeQuestionCount(group.question_count ?? group.questionCount);
  const answerKey = normalizeAnswerKey(group.answer_key ?? group.answerKey);
  const rosterSource = Array.isArray(group.roster) ? group.roster : [];
  const resultsSource = Array.isArray(group.results) ? group.results : [];

  const normalized: Group = {
    id: normalizeTrim(group.id) || generateId(),
    course_name: courseName,
    question_count: questionCount,
    grade_level: normalizeOptional(group.grade_level),
    section: normalizeOptional(group.section),
    answer_key: answerKey,
    roster: rosterSource.map(item => normalizeRosterStudent(item as Partial<ClassRosterStudent>)),
    results: resultsSource.map(item => normalizeStudentResult(item as Partial<StudentResult>)),
    created_at: Number(group.created_at ?? group.createdAt ?? Date.now()) || Date.now(),
  };

  return withLegacyFields(normalized);
};

const normalizeGroups = (groups: unknown): Group[] => {
  if (!Array.isArray(groups)) return [];
  return groups.map(group => normalizeGroup(group as Partial<Group>));
};

const createGroupFromInput = (input: CreateClassInput): Group => {
  return normalizeGroup({
    id: generateId(),
    course_name: input.course_name,
    question_count: input.question_count,
    grade_level: input.grade_level,
    section: input.section,
    answer_key: {},
    roster: [],
    results: [],
    created_at: Date.now(),
  });
};

const mergeRoster = (
  existingRoster: ClassRosterStudent[],
  incomingRoster: ClassRosterStudent[],
  mode: MergeMode
): { roster: ClassRosterStudent[]; summary: RosterMergeSummary } => {
  const summary: RosterMergeSummary = { added: 0, skipped: 0, replaced: 0 };
  const mapByNumber = new Map<string, ClassRosterStudent>();

  existingRoster.forEach(student => {
    const key = normalizeStudentNumber(student.student_number);
    if (key) mapByNumber.set(key, student);
  });

  incomingRoster.forEach(rawStudent => {
    const student = normalizeRosterStudent(rawStudent);
    const key = normalizeStudentNumber(student.student_number);
    if (!key) {
      summary.skipped += 1;
      return;
    }

    const existing = mapByNumber.get(key);
    if (!existing) {
      mapByNumber.set(key, student);
      summary.added += 1;
      return;
    }

    if (mode === 'replace') {
      mapByNumber.set(key, {
        ...existing,
        ...student,
        id: existing.id,
        created_at: existing.created_at,
      });
      summary.replaced += 1;
      return;
    }

    summary.skipped += 1;
  });

  const roster = Array.from(mapByNumber.values()).sort((a, b) => {
    const aNo = normalizeStudentNumber(a.student_number);
    const bNo = normalizeStudentNumber(b.student_number);
    return aNo.localeCompare(bNo, 'tr');
  });

  return { roster, summary };
};

const cleanupImagePaths = (paths: Array<string | undefined>) => {
  paths
    .filter((path): path is string => Boolean(path && path.trim().length > 0))
    .forEach((path) => {
      void deleteResultImage(path);
    });
};

export const useStore = create<StoreState>()(
  persist(
    (set) => ({
      groups: [],

      addGroup: (name, questionCount) =>
        {
          const newGroup = createGroupFromInput({
            course_name: name,
            question_count: questionCount,
          });
          set((state) => ({
            groups: [...state.groups, newGroup],
          }));
          return newGroup.id;
        },

      addClass: (input) => {
        const newGroup = createGroupFromInput(input);
        set((state) => ({
          groups: [...state.groups, newGroup],
        }));
        return newGroup.id;
      },

      removeGroup: (id) => {
        const staleImagePaths: string[] = [];

        set((state) => ({
          groups: state.groups.filter((g) => {
            if (g.id !== id) return true;
            (g.results || []).forEach((result) => {
              if (result.gradedImagePath) staleImagePaths.push(result.gradedImagePath);
            });
            return false;
          }),
        }));

        cleanupImagePaths(staleImagePaths);
      },

      updateGroupName: (groupId, name) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? withLegacyFields({
                ...g,
                course_name: normalizeTrim(name) || g.course_name,
              })
              : g
          ),
        })),

      updateGroupMeta: (groupId, payload) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? withLegacyFields({
                ...g,
                course_name: normalizeTrim(payload.course_name) || g.course_name,
                question_count: normalizeQuestionCount(payload.question_count ?? g.question_count),
                grade_level: normalizeOptional(payload.grade_level),
                section: normalizeOptional(payload.section),
              })
              : g
          ),
        })),

      updateAnswerKey: (groupId, answerKey) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? withLegacyFields({
                ...g,
                answer_key: normalizeAnswerKey(answerKey),
              })
              : g
          ),
        })),

      addStudentResult: (groupId, result) => {
        const staleImagePaths: string[] = [];

        set((state) => ({
          groups: state.groups.map((g) => {
            if (g.id !== groupId) return g;

            const normalized = normalizeStudentResult(result);
            const studentNo = normalizeStudentNumber(normalized.studentNumber);
            const currentResults = [...(g.results || [])];

            if (!normalized.pending && studentNo) {
              const duplicateIndex = currentResults.findIndex((item) =>
                !item.pending && normalizeStudentNumber(item.studentNumber) === studentNo
              );

              if (duplicateIndex >= 0) {
                const existing = currentResults[duplicateIndex];
                if (existing.gradedImagePath && existing.gradedImagePath !== normalized.gradedImagePath) {
                  staleImagePaths.push(existing.gradedImagePath);
                }

                currentResults[duplicateIndex] = normalizeStudentResult({
                  ...existing,
                  ...normalized,
                  id: existing.id,
                });
              } else {
                currentResults.push(normalized);
              }
            } else {
              currentResults.push(normalized);
            }

            return withLegacyFields({
              ...g,
              results: currentResults,
            });
          }),
        }));

        cleanupImagePaths(staleImagePaths);
      },

      updateStudentResult: (groupId, resultId, updatedData) => {
        const staleImagePaths: string[] = [];

        set((state) => ({
          groups: state.groups.map((g) => {
            if (g.id !== groupId) return g;

            const currentResults = [...(g.results || [])];
            const currentIndex = currentResults.findIndex((item) => item.id === resultId);
            if (currentIndex < 0) return g;

            const current = currentResults[currentIndex];
            const merged = normalizeStudentResult({
              ...current,
              ...updatedData,
              id: current.id,
            });

            if (current.gradedImagePath && current.gradedImagePath !== merged.gradedImagePath) {
              staleImagePaths.push(current.gradedImagePath);
            }

            let nextResults = currentResults.map((item) => (item.id === resultId ? merged : item));
            const studentNo = normalizeStudentNumber(merged.studentNumber);

            if (!merged.pending && studentNo) {
              nextResults = nextResults.filter((item) => {
                if (item.id === resultId) return true;
                const duplicated = !item.pending && normalizeStudentNumber(item.studentNumber) === studentNo;
                if (duplicated && item.gradedImagePath && item.gradedImagePath !== merged.gradedImagePath) {
                  staleImagePaths.push(item.gradedImagePath);
                }
                return !duplicated;
              });
            }

            return withLegacyFields({
              ...g,
              results: nextResults,
            });
          }),
        }));

        cleanupImagePaths(staleImagePaths);
      },

      removeStudentResult: (groupId, resultId) => {
        const staleImagePaths: string[] = [];

        set((state) => ({
          groups: state.groups.map((g) => {
            if (g.id !== groupId) return g;

            const filtered = (g.results || []).filter((r) => {
              if (r.id !== resultId) return true;
              if (r.gradedImagePath) staleImagePaths.push(r.gradedImagePath);
              return false;
            });

            return withLegacyFields({
              ...g,
              results: filtered,
            });
          }),
        }));

        cleanupImagePaths(staleImagePaths);
      },

      setRosterStudents: (groupId, students) =>
        set((state) => ({
          groups: state.groups.map((g) =>
            g.id === groupId
              ? withLegacyFields({
                ...g,
                roster: students.map(student => normalizeRosterStudent(student)),
              })
              : g
          ),
        })),

      addRosterStudents: (groupId, students, mode = 'skip') => {
        let summary: RosterMergeSummary = { added: 0, skipped: 0, replaced: 0 };
        set((state) => ({
          groups: state.groups.map((g) => {
            if (g.id !== groupId) return g;
            const merged = mergeRoster(g.roster || [], students || [], mode);
            summary = merged.summary;
            return withLegacyFields({
              ...g,
              roster: merged.roster,
            });
          }),
        }));
        return summary;
      },

      transferStudentResult: (sourceGroupId, targetGroupId, resultId, mode = 'skip') => {
        let summary: TransferResultSummary = { ok: false, reason: 'source-not-found' };
        const staleImagePaths: string[] = [];

        set((state) => {
          const sourceGroup = state.groups.find((g) => g.id === sourceGroupId);
          if (!sourceGroup) return state;

          const targetGroup = state.groups.find((g) => g.id === targetGroupId);
          if (!targetGroup) {
            summary = { ok: false, reason: 'target-not-found' };
            return state;
          }

          const studentResult = (sourceGroup.results || []).find((r) => r.id === resultId);
          if (!studentResult) {
            summary = { ok: false, reason: 'result-not-found' };
            return state;
          }

          const studentNo = normalizeStudentNumber(studentResult.studentNumber);
          const targetResults = targetGroup.results || [];
          const duplicated = studentNo
            ? targetResults.find((r) => normalizeStudentNumber(r.studentNumber) === studentNo)
            : undefined;

          if (duplicated && mode !== 'replace') {
            summary = { ok: false, reason: 'duplicate-skipped' };
            return state;
          }

          summary = { ok: true };

          return {
            ...state,
            groups: state.groups.map((g) => {
              if (g.id === sourceGroupId) {
                return withLegacyFields({
                  ...g,
                  results: (g.results || []).filter((r) => r.id !== resultId),
                });
              }

              if (g.id === targetGroupId) {
                const nextResults = duplicated
                  ? (g.results || []).filter((r) => r.id !== duplicated.id)
                  : [...(g.results || [])];

                if (duplicated?.gradedImagePath && duplicated.gradedImagePath !== studentResult.gradedImagePath) {
                  staleImagePaths.push(duplicated.gradedImagePath);
                }

                nextResults.push(studentResult);
                return withLegacyFields({
                  ...g,
                  results: nextResults,
                });
              }

              return g;
            }),
          };
        });

        cleanupImagePaths(staleImagePaths);

        return summary;
      },
    }),
    {
      name: 'omr-storage',
      storage: createJSONStorage(() => AsyncStorage),
      version: 2,
      migrate: (persistedState: any) => {
        if (!persistedState || typeof persistedState !== 'object') {
          return { groups: [] };
        }
        return {
          ...persistedState,
          groups: normalizeGroups(persistedState.groups),
        };
      },
      merge: (persistedState: any, currentState: any) => {
        const base = {
          ...currentState,
          ...(persistedState || {}),
        };
        return {
          ...base,
          groups: normalizeGroups(base.groups),
        };
      },
    }
  )
);
