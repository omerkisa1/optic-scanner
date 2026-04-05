export interface StudentResult {
  id: string;
  name: string;
  studentNumber: string;
  correct: number;
  wrong: number;
  blank: number;
  score: number;
  answers: Record<string, string>;
  scannedAt: number;
  pending?: boolean; // taranıyor durumu
}

export interface Group {
  id: string;
  name: string;
  questionCount: number;
  answerKey: Record<string, string>;
  results?: StudentResult[];
  createdAt: number;
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
  error?: string;
}

export type Exam = Group;
