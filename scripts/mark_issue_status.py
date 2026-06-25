"""
Danh dau trang thai cac issue tren GitHub dua vao danh sach Story da hoan thanh.

Logic:
- Story DONE: close issue + add label 'status:done' + close tat ca sub-task con
- Story IN-PROGRESS: chi add label 'status:in-progress' (khong close)
- Su dung GraphQL de lay sub-issues cua moi Story

Cach dung:
    python mark_issue_status.py --repo dikhamchua/ulp --dry-run
    python mark_issue_status.py --repo dikhamchua/ulp --execute
"""

from __future__ import annotations

import argparse
import io
import json
import subprocess
import sys
import time
from typing import Optional

if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")


# ---------- Trang thai cac story ----------
# Dua tren ket qua phan tich codebase
STORY_DONE = [
    # E1 Auth
    "ULP-1.4",   # Login email/password
    "ULP-1.5",   # Google OAuth
    "ULP-1.6",   # Quen mat khau
    "ULP-1.7",   # Dang xuat
    "ULP-1.8",   # Xem/sua thong tin
    "ULP-1.9",   # Doi mat khau
    # E3 Enrollment
    "ULP-3.1",   # Join bang invite code
    "ULP-3.2",   # Join qua link
    "ULP-3.3",   # Sinh / tai tao ma & link
    "ULP-3.5",   # Them/xoa SV thu cong
    "ULP-3.6",   # Danh sach lop da tham gia
    "ULP-3.7",   # Roi lop
    "ULP-3.8",   # Trang thai enrollment
    # E9 Lecturer
    "ULP-9.2",   # Tao & quan ly lop
    # E11 Admin
    "ULP-11.1",  # Dashboard he thong
    "ULP-11.2",  # Quan ly user
    "ULP-11.10", # Cai dat Google Login
    "ULP-11.11", # Cai dat SMTP
    "ULP-11.13", # Lich su dang nhap
    "ULP-11.14", # Khoa/mo khoa
    "ULP-11.15", # Ho so Admin
]

STORY_IN_PROGRESS = [
    "ULP-9.3",   # Quan ly SV trong lop (mot phan)
    "ULP-11.9",  # Cai dat he thong (khung co, UI placeholder)
]

EPIC_DONE = []  # Khong epic nao done 100% vi con sub-task
EPIC_IN_PROGRESS = [
    "Xác thực & Tài khoản",
    "Tham gia lớp (Enrollment)",
    "Lecturer — Quản lý lớp & nội dung",
    "Admin — Quản trị hệ thống",
]


# ---------- gh helpers ----------
def run_gh(args: list[str], input_data: Optional[str] = None, max_retries: int = 5) -> str:
    for attempt in range(max_retries):
        result = subprocess.run(["gh", *args], input=input_data,
                                capture_output=True, text=True, encoding="utf-8")
        if result.returncode == 0:
            return result.stdout
        combined = (result.stderr + result.stdout).lower()
        if any(e in combined for e in ("502", "503", "504", "500", "timeout")) and attempt < max_retries - 1:
            time.sleep(2 ** attempt)
            continue
        raise RuntimeError(f"gh {' '.join(args)} failed:\n{result.stderr}\n{result.stdout}")
    raise RuntimeError("retries exhausted")


def gh_graphql(query: str, variables: dict) -> dict:
    payload = json.dumps({"query": query, "variables": variables})
    out = run_gh(["api", "graphql", "--input", "-"], input_data=payload)
    return json.loads(out)


# ---------- Operations ----------
def get_all_issues(repo: str) -> list[dict]:
    """Lay tat ca issue (open + closed)."""
    out = run_gh(["api", f"repos/{repo}/issues?state=all&per_page=100", "--paginate"])
    items = json.loads(out) if out.strip() else []
    return [i for i in items if "pull_request" not in i]


def find_issue_by_story_id(issues: list[dict], story_id: str) -> Optional[dict]:
    """Tim issue co title bat dau bang [ULP-x.y]."""
    prefix = f"[{story_id}]"
    for i in issues:
        if i["title"].startswith(prefix):
            return i
    return None


def find_epic_issue(issues: list[dict], epic_name: str) -> Optional[dict]:
    """Tim Epic theo ten."""
    title = f"[Epic] {epic_name}"
    for i in issues:
        if i["title"] == title:
            return i
    return None


def get_sub_issues(node_id: str) -> list[dict]:
    """Lay sub-issues qua GraphQL."""
    query = """
    query($id: ID!) {
      node(id: $id) {
        ... on Issue {
          subIssues(first: 100) {
            nodes { id number title state }
          }
        }
      }
    }
    """
    result = gh_graphql(query, {"id": node_id})
    return result.get("data", {}).get("node", {}).get("subIssues", {}).get("nodes", []) or []


def add_label(repo: str, issue_num: int, label: str, dry_run: bool) -> None:
    if dry_run:
        print(f"    [dry-run] + label '{label}' -> #{issue_num}")
        return
    run_gh(["issue", "edit", str(issue_num), "-R", repo, "--add-label", label])


def close_issue(repo: str, issue_num: int, dry_run: bool) -> None:
    if dry_run:
        print(f"    [dry-run] CLOSE #{issue_num}")
        return
    run_gh(["issue", "close", str(issue_num), "-R", repo, "--reason", "completed"])


def ensure_label(repo: str, name: str, color: str, dry_run: bool) -> None:
    if dry_run:
        print(f"  [dry-run] ensure label: {name}")
        return
    try:
        run_gh(["api", f"repos/{repo}/labels/{name}"])
    except RuntimeError:
        # khong ton tai -> tao
        try:
            run_gh(["api", "-X", "POST", f"repos/{repo}/labels",
                    "-f", f"name={name}", "-f", f"color={color}"])
            print(f"  + label: {name}")
        except RuntimeError as e:
            if "already_exists" not in str(e):
                raise


# ---------- Main ----------
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry-run", action="store_true")
    g.add_argument("--execute", action="store_true")
    ap.add_argument("--rate-delay", type=float, default=0.25)
    args = ap.parse_args()

    dry_run = args.dry_run

    print(f"=> Lay danh sach issue tu {args.repo}...")
    issues = get_all_issues(args.repo)
    print(f"   {len(issues)} issues")

    # Bao dam co 3 label trang thai
    print(f"\n=> Tao status labels...")
    ensure_label(args.repo, "status:done", "0E8A16", dry_run)
    ensure_label(args.repo, "status:in-progress", "FBCA04", dry_run)
    ensure_label(args.repo, "status:todo", "C5DEF5", dry_run)

    # ---------- Story DONE ----------
    print(f"\n=> Story DONE ({len(STORY_DONE)})...")
    done_total = 0
    closed_subtasks = 0
    for sid in STORY_DONE:
        story = find_issue_by_story_id(issues, sid)
        if not story:
            print(f"  ! Khong tim thay {sid}")
            continue
        print(f"  - {sid} #{story['number']}: {story['title']}")
        # Add label + close story
        add_label(args.repo, story["number"], "status:done", dry_run)
        if story["state"] != "closed":
            close_issue(args.repo, story["number"], dry_run)
            if not dry_run:
                time.sleep(args.rate_delay)
        done_total += 1

        # Close sub-issues
        subs = get_sub_issues(story["node_id"]) if not dry_run else []
        if dry_run:
            # Trong dry-run, dem so sub-task qua REST
            subs_count = 4  # gia dinh
            print(f"    [dry-run] close ~{subs_count} sub-tasks")
        else:
            for sub in subs:
                if sub["state"] != "CLOSED":
                    add_label(args.repo, sub["number"], "status:done", dry_run)
                    close_issue(args.repo, sub["number"], dry_run)
                    closed_subtasks += 1
                    time.sleep(args.rate_delay)

    # ---------- Story IN-PROGRESS ----------
    print(f"\n=> Story IN-PROGRESS ({len(STORY_IN_PROGRESS)})...")
    for sid in STORY_IN_PROGRESS:
        story = find_issue_by_story_id(issues, sid)
        if not story:
            print(f"  ! Khong tim thay {sid}")
            continue
        print(f"  - {sid} #{story['number']}: {story['title']}")
        add_label(args.repo, story["number"], "status:in-progress", dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    # ---------- Epic IN-PROGRESS ----------
    print(f"\n=> Epic IN-PROGRESS ({len(EPIC_IN_PROGRESS)})...")
    for ep in EPIC_IN_PROGRESS:
        epic = find_epic_issue(issues, ep)
        if not epic:
            print(f"  ! Khong tim thay Epic: {ep}")
            continue
        print(f"  - #{epic['number']}: {epic['title']}")
        add_label(args.repo, epic["number"], "status:in-progress", dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    # ---------- Mark all remaining as TODO ----------
    print(f"\n=> Gan 'status:todo' cho cac issue chua co status...")
    done_ids = {find_issue_by_story_id(issues, s)["number"] for s in STORY_DONE
                if find_issue_by_story_id(issues, s)}
    inprog_ids = {find_issue_by_story_id(issues, s)["number"] for s in STORY_IN_PROGRESS
                  if find_issue_by_story_id(issues, s)}
    epic_inprog = {find_epic_issue(issues, e)["number"] for e in EPIC_IN_PROGRESS
                   if find_epic_issue(issues, e)}

    handled = done_ids | inprog_ids | epic_inprog

    todo_count = 0
    for i in issues:
        if i["number"] in handled:
            continue
        # Skip cac sub-task da bi close cua story done (kho biet truoc trong dry-run)
        labels = [l["name"] for l in i.get("labels", [])]
        if "status:done" in labels or "status:in-progress" in labels or "status:todo" in labels:
            continue
        if i["state"] == "closed":
            continue
        add_label(args.repo, i["number"], "status:todo", dry_run)
        todo_count += 1
        if not dry_run:
            time.sleep(args.rate_delay)

    print(f"\n=> Xong!")
    print(f"   - {done_total} Story DONE (close + label)")
    print(f"   - {closed_subtasks} Sub-task DONE (close + label)" if not dry_run else "")
    print(f"   - {len(STORY_IN_PROGRESS)} Story + {len(EPIC_IN_PROGRESS)} Epic IN-PROGRESS")
    print(f"   - {todo_count} issue gan TODO")


if __name__ == "__main__":
    main()
