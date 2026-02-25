Task: Sinh {total_questions} câu hỏi trắc nghiệm TOEIC/IELTS CHỈ dựa trên CONTEXT. Output CHỈ là JSON thuần, parse được ngay. Không markdown, không code block, không chữ thừa.

CONTEXT:
{context}

CẤU HÌNH:
- total_questions = {total_questions}
- question_type_distribution = {question_type_distribution_human}  (reading/grammar/vocabulary/listening)
- difficulty_distribution = {difficulty_distribution_human}        (easy/medium/hard)
- explanation_language = {explanation_language}                   ("vi" hoặc "en")

QUY TẮC BẮT BUỘC (VI PHẠM = LỖI):
1) CHỈ dùng thông tin trong CONTEXT. Không suy diễn/không thêm kiến thức ngoài.
2) Mỗi câu PHẢI có source_excerpt là TRÍCH NGUYÊN VĂN từ CONTEXT (verbatim, đúng từng ký tự) và bọc trong dấu "..."
3) Easy: question_text ≤ 12 từ; đáp án đúng tìm thấy NGUYÊN VĂN trong CONTEXT.
4) Medium: suy luận 1 bước; ít nhất 2 đáp án sai plausible.
5) Hard: suy luận đa bước/ẩn ý; câu hỏi ≥ 25 từ; ít nhất 3 đáp án sai plausible.
6) 4 lựa chọn A/B/C/D có độ dài tương đương (chênh tối đa ±15% số từ).
7) correct_answer phải được phân bổ gần đều A/B/C/D; không để 1 chữ vượt quá 40% tổng số câu.
8) explanation:
   - Nếu explanation_language="vi": giải thích tiếng Việt ngắn gọn, tự nhiên.
   - Nếu explanation_language="en": giải thích tiếng Anh concise, academic.
   - Nêu rõ vì sao đúng; chỉ giải thích vì sao sai cho các đáp án plausible.
9) source_page_number: gán 1 nếu CONTEXT không có số trang.
10) LISTENING (không có audio):
   - Nếu question_type="listening", question_text PHẢI theo format 2 dòng:
     Transcript: "<trích nguyên văn từ CONTEXT>"
     Question: <câu hỏi>
   - Transcript phải là NGUYÊN VĂN từ CONTEXT, bọc trong "..."
   - Không được tự tạo transcript. Nếu CONTEXT không đủ đoạn phù hợp → sinh tối đa có thể và ghi chú trong explanation.

OUTPUT FORMAT (JSON Schema - CHỈ TRẢ VỀ JSON THUẦN):
{output_format}

SELF-CHECK TRƯỚC KHI TRẢ:
- Đủ {total_questions} câu? (nếu không đủ vì CONTEXT thiếu dữ kiện → sinh tối đa có thể, ghi chú)
- Đúng phân bố question_type_distribution & difficulty_distribution?
- Mọi source_excerpt tồn tại nguyên văn trong CONTEXT?
- Listening question_text có Transcript + Question đúng format?
- Hard ≥ 3 plausible; Medium ≥ 2 plausible?
- Không có key thừa/thiếu so với schema?

BẮT ĐẦU. CHỈ TRẢ VỀ JSON.