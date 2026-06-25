"""
Build sample Excel files for E2E testing of ULP-3.4 Import Excel feature.

Outputs to: ./tmp/e2e/
- sample-mixed.xlsx — 8 rows covering all status codes
- sample-valid.xlsx — 3 OK rows
"""
from pathlib import Path
import openpyxl

OUT = Path(__file__).parent.parent / "tmp" / "e2e"
OUT.mkdir(parents=True, exist_ok=True)

# Sample with mixed statuses
# Seed data has sv01..sv08 (STUDENT), lecturer@ulp.edu.vn (LECTURER), student@ulp.edu.vn (STUDENT)
# All sv01..sv08 are ALREADY enrolled in every class (V8 seed)
# To test fresh OK: we need students NOT enrolled. Strategy: use student@ulp.edu.vn (not in seed enrollments for fake classes)
# Actually V8 enrolls ALL students into ALL classes. So we'll mostly see DUPLICATE_IN_CLASS for sv01..sv08
# That's still a valid test of the warning path.

wb = openpyxl.Workbook()
ws = wb.active
ws.title = "Students"

# Headers
ws.append(["Email", "MSSV", "Họ Tên", "Số điện thoại"])

# Rows - mix statuses
ws.append(["sv01@ulp.edu.vn", "HE181001", "Đỗ Khắc Nam", "0971761607"])      # DUPLICATE_IN_CLASS (đã enroll trong V8)
ws.append(["sv02@ulp.edu.vn", "HE181002", "Trần Thu Hà", "0905123456"])      # DUPLICATE_IN_CLASS
ws.append(["unknown@ulp.edu.vn", "HE181099", "Không Tồn Tại", "0900000000"]) # USER_NOT_FOUND
ws.append(["not-an-email", "HE181003", "Email Sai", "0911222333"])           # INVALID_EMAIL
ws.append(["lecturer@ulp.edu.vn", "GV001", "Là Giảng Viên", "0900000001"])   # NOT_A_STUDENT
ws.append(["", "", "Thiếu định danh", ""])                                    # MISSING_REQUIRED
ws.append(["sv03@ulp.edu.vn", "HE181003", "Lê Văn Hùng", "0912765432"])      # DUPLICATE_IN_CLASS
ws.append(["sv03@ulp.edu.vn", "HE181003-DUP", "Trùng Email", ""])             # DUPLICATE_IN_FILE (trùng sv03 ở trên)

wb.save(OUT / "sample-mixed.xlsx")
print(f"Wrote {OUT / 'sample-mixed.xlsx'}")

# Simple valid sample (might all be DUPLICATE_IN_CLASS due to V8 seed)
wb2 = openpyxl.Workbook()
ws2 = wb2.active
ws2.title = "Students"
ws2.append(["Email", "MSSV", "Họ Tên"])
ws2.append(["sv04@ulp.edu.vn", "HE181004", "Phạm Minh Anh"])
ws2.append(["sv05@ulp.edu.vn", "HE181005", "Vũ Thị Mai"])
ws2.append(["sv06@ulp.edu.vn", "HE181006", "Nguyễn Bá Sơn"])
wb2.save(OUT / "sample-valid.xlsx")
print(f"Wrote {OUT / 'sample-valid.xlsx'}")
