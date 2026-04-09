export interface StudentResult {
  id: string;
  name: string;
  studentNumber: string;
  correct: number;
  wrong: number;
  blank: number;
  score: number;
  answers: Record<string, string>;
  gradedImagePath?: string;
  scannedAt: number;
  pending?: boolean;
}

export interface ClassRosterStudent {
  id: string;
  full_name: string;
  student_number: string;
  grade_level?: string;
  section?: string;
  created_at: number;
}

export interface Group {
  id: string;
  course_name: string;
  question_count: number;
  grade_level?: string;
  section?: string;
  answer_key: Record<string, string>;
  roster: ClassRosterStudent[];
  results?: StudentResult[];
  created_at: number;
  name?: string;
  questionCount?: number;
  answerKey?: Record<string, string>;
  createdAt?: number;
}

export interface OptionSchema {
  val: string;
  x: number;
  y: number;
}

export interface QuestionSchema {
  q_no: number;
  options: OptionSchema[];
}

export interface BackendSchema {
  template_id: string;
  base_aspect_ratio: number;
  anchors: any[];
  fields: any[];
  questions: QuestionSchema[];
  metadata: any;
}

export interface ScanResult {
  status: string;
  student_info: {
    name: string;
    student_number: string;
  };
  answers: Record<string, string>;
  metadata: any;
  formImagePath?: string;
  error?: string;
}

export type Exam = Group;

export interface CreateClassInput {
  course_name: string;
  question_count: number;
  grade_level?: string;
  section?: string;
}
