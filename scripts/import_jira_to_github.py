"""
Import Jira backlog CSV vao GitHub Issues.

Cau truc CSV (3 tang):
    Epic (E1, E2...)      -> Issue + label type:epic
        Story (ULP-x.y)   -> Sub-issue cua Epic + label type:story
            Sub-task      -> Sub-issue cua Story + label type:subtask

Cach dung:
    # 1. Xem truoc cac action se thuc hien (khong goi API)
    python import_jira_to_github.py --csv ../JIRA_BACKLOG_ULP.csv --repo owner/name --dry-run

    # 2. Chay that
    python import_jira_to_github.py --csv ../JIRA_BACKLOG_ULP.csv --repo owner/name --execute

    # 3. Chi tao labels + milestones (chua tao issues)
    python import_jira_to_github.py --csv ../JIRA_BACKLOG_ULP.csv --repo owner/name --execute --setup-only

Yeu cau:
    - gh CLI da login (`gh auth status` -> Logged in)
    - Python 3.10+
    - Repo da ton tai tren GitHub

Tinh nang:
    - Idempotent: bo qua issue/label/milestone trung ten
    - Tu link sub-issue (Epic -> Story -> Sub-task) qua GraphQL
    - Tu tao tat ca labels (auth, be, fe, db, test, priority:*, type:*, sprint:*)
    - Tu tao milestones theo Sprint
    - Co tien do (progress bar) khi chay
"""

from __future__ import annotations

import argparse
import csv
import io
import json
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

# Windows console: bat buoc UTF-8 de in tieng Viet
if sys.platform == "win32":
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
    sys.stderr = io.TextIOWrapper(sys.stderr.buffer, encoding="utf-8", errors="replace")


# ---------- Cau hinh mau sac label ----------
PRIORITY_COLORS = {
    "Highest": "B60205",
    "High":    "D93F0B",
    "Medium":  "FBCA04",
    "Low":     "0E8A16",
}

TYPE_COLORS = {
    "type:epic":    "6F42C1",
    "type:story":   "1D76DB",
    "type:subtask": "C5DEF5",
}

TAG_COLORS = {
    # Functional area
    "auth":         "5319E7",
    "core":         "0052CC",
    "course":       "1D76DB",
    "enrollment":   "0E8A16",
    "lesson":       "FBCA04",
    "flashcard":    "D93F0B",
    "test":         "B60205",
    "assignment":   "5319E7",
    "notification": "C5DEF5",
    "messaging":    "BFD4F2",
    "dashboard":    "0052CC",
    "admin":        "B60205",
    "ai":           "F9D0C4",
    "optional":     "EEEEEE",
    # Layer
    "be":           "C2E0C6",
    "fe":           "FEF2C0",
    "db":           "F9D0C4",
    # Role
    "student":      "BFD4F2",
    "lecturer":     "C5DEF5",
    "head":         "D4C5F9",
}

SPRINT_COLOR = "EDEDED"


# ---------- Data classes ----------
@dataclass
class CsvRow:
    issue_id: str
    parent_id: str
    issue_type: str
    summary: str
    description: str
    priority: str
    labels: str
    epic_name: str
    epic_link: str
    sprint: str


@dataclass
class IssueSpec:
    """Mot issue se tao tren GitHub."""
    csv_id: str                  # E1, ULP-1.1, hoac auto-gen cho sub-task
    title: str
    body: str
    labels: list[str]
    milestone: Optional[str]
    issue_type: str              # epic / story / subtask
    parent_csv_id: Optional[str] = None
    # Sau khi tao tren GH:
    gh_number: Optional[int] = None
    gh_node_id: Optional[str] = None


# ---------- gh CLI wrapper ----------
TRANSIENT_ERRORS = ("HTTP 502", "HTTP 503", "HTTP 504", "HTTP 500", "timeout", "connection reset")


def run_gh(args: list[str], input_data: Optional[str] = None, max_retries: int = 5) -> str:
    """Goi gh CLI, tra ve stdout. Tu retry voi loi transient (502/503/504)."""
    last_err = ""
    for attempt in range(max_retries):
        result = subprocess.run(
            ["gh", *args],
            input=input_data,
            capture_output=True,
            text=True,
            encoding="utf-8",
        )
        if result.returncode == 0:
            return result.stdout
        last_err = f"stderr: {result.stderr}\nstdout: {result.stdout}"
        # Kiem tra co phai loi transient khong
        combined = (result.stderr + result.stdout).lower()
        is_transient = any(e.lower() in combined for e in TRANSIENT_ERRORS)
        if not is_transient or attempt == max_retries - 1:
            raise RuntimeError(f"gh {' '.join(args)} failed:\n{last_err}")
        wait = 2 ** attempt  # exponential backoff: 1, 2, 4, 8, 16s
        print(f"  ! Transient error (attempt {attempt+1}/{max_retries}), retry sau {wait}s...")
        time.sleep(wait)
    raise RuntimeError(f"gh {' '.join(args)} failed after retries:\n{last_err}")


def gh_api(method: str, endpoint: str, fields: Optional[dict] = None) -> dict:
    """Goi GitHub REST API qua gh."""
    args = ["api", "-X", method, endpoint]
    if fields:
        for k, v in fields.items():
            if isinstance(v, bool):
                args += ["-F", f"{k}={'true' if v else 'false'}"]
            elif isinstance(v, int):
                args += ["-F", f"{k}={v}"]
            else:
                args += ["-f", f"{k}={v}"]
    out = run_gh(args)
    return json.loads(out) if out.strip() else {}


def gh_graphql(query: str, variables: dict) -> dict:
    """Goi GitHub GraphQL qua gh."""
    payload = json.dumps({"query": query, "variables": variables})
    out = run_gh(["api", "graphql", "--input", "-"], input_data=payload)
    return json.loads(out)


# ---------- CSV parsing ----------
def parse_csv(path: Path) -> list[CsvRow]:
    rows: list[CsvRow] = []
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        for r in reader:
            # Bo qua dong trong
            if not (r.get("Issue Type") or "").strip():
                continue
            rows.append(CsvRow(
                issue_id   = (r.get("Issue ID")    or "").strip(),
                parent_id  = (r.get("Parent ID")   or "").strip(),
                issue_type = (r.get("Issue Type")  or "").strip(),
                summary    = (r.get("Summary")     or "").strip(),
                description= (r.get("Description") or "").strip(),
                priority   = (r.get("Priority")    or "").strip(),
                labels     = (r.get("Labels")      or "").strip(),
                epic_name  = (r.get("Epic Name")   or "").strip(),
                epic_link  = (r.get("Epic Link")   or "").strip(),
                sprint     = (r.get("Sprint")      or "").strip(),
            ))
    return rows


def build_issue_specs(rows: list[CsvRow]) -> list[IssueSpec]:
    """Convert CSV rows -> IssueSpec, tu sinh ID cho sub-task."""
    specs: list[IssueSpec] = []
    # Dem sub-task theo story de sinh ID phu
    subtask_counter: dict[str, int] = {}

    # Map ten Epic -> Epic ID (E1, E2...) de noi Story.Epic Link -> Epic
    epic_name_to_id: dict[str, str] = {
        r.issue_id: r.summary for r in rows if r.issue_type == "Epic"
    }
    # Reverse: ten Epic -> ID
    epic_name_to_epic_id: dict[str, str] = {v: k for k, v in epic_name_to_id.items()}

    for r in rows:
        labels_list = [l.strip() for l in r.labels.split(",") if l.strip()]

        # Them label type, priority, sprint
        type_label = ""
        if r.issue_type == "Epic":
            type_label = "type:epic"
        elif r.issue_type == "Story":
            type_label = "type:story"
        elif r.issue_type == "Sub-task":
            type_label = "type:subtask"

        prio_label = f"priority:{r.priority.lower()}" if r.priority else ""
        sprint_label = f"sprint:{r.sprint.lower().replace(' ', '-')}" if r.sprint else ""

        all_labels = list({*labels_list, type_label, prio_label, sprint_label})
        all_labels = [l for l in all_labels if l]

        # Tinh parent
        parent_csv_id: Optional[str] = None
        if r.issue_type == "Epic":
            csv_id = r.issue_id
        elif r.issue_type == "Story":
            csv_id = r.issue_id
            # Story.Epic Link la ten Epic -> map ve Epic ID
            if r.epic_link:
                parent_csv_id = epic_name_to_epic_id.get(r.epic_link)
        elif r.issue_type == "Sub-task":
            parent_csv_id = r.parent_id  # ULP-x.y
            # Tu sinh ID
            subtask_counter.setdefault(parent_csv_id, 0)
            subtask_counter[parent_csv_id] += 1
            csv_id = f"{parent_csv_id}.sub{subtask_counter[parent_csv_id]}"
        else:
            continue  # bo qua loai khac

        # Build title
        if r.issue_type == "Epic":
            title = f"[Epic] {r.summary}"
        elif r.issue_type == "Story":
            title = f"[{r.issue_id}] {r.summary}"
        else:
            title = f"  {r.summary}"  # sub-task giu nguyen prefix [BE]/[FE]/...

        # Build body
        body_parts = [r.description] if r.description else []
        if r.issue_type == "Story" and r.epic_link:
            body_parts.append(f"\n**Epic:** {r.epic_link}")
        if r.issue_type == "Sub-task" and r.parent_id:
            body_parts.append(f"\n**Story:** {r.parent_id}")
        if r.priority:
            body_parts.append(f"**Priority:** {r.priority}")
        if r.sprint:
            body_parts.append(f"**Sprint:** {r.sprint}")
        body = "\n".join(body_parts)

        specs.append(IssueSpec(
            csv_id=csv_id,
            title=title,
            body=body,
            labels=all_labels,
            milestone=r.sprint or None,
            issue_type=type_label.split(":")[1] if type_label else "",
            parent_csv_id=parent_csv_id,
        ))

    return specs


# ---------- GitHub operations ----------
def get_or_create_label(repo: str, name: str, color: str, description: str = "",
                       existing: dict = None, dry_run: bool = False) -> None:
    if existing is not None and name in existing:
        return
    if dry_run:
        print(f"  [dry-run] CREATE label: {name} (#{color})")
        return
    try:
        gh_api("POST", f"repos/{repo}/labels", {
            "name": name,
            "color": color,
            "description": description[:100],
        })
        print(f"  + label: {name}")
    except RuntimeError as e:
        if "already_exists" in str(e):
            return
        raise


def get_existing_labels(repo: str) -> dict[str, dict]:
    out = run_gh(["api", f"repos/{repo}/labels", "--paginate"])
    items = json.loads(out) if out.strip() else []
    return {x["name"]: x for x in items}


def get_existing_milestones(repo: str) -> dict[str, dict]:
    out = run_gh(["api", f"repos/{repo}/milestones?state=all", "--paginate"])
    items = json.loads(out) if out.strip() else []
    return {x["title"]: x for x in items}


def get_existing_issues(repo: str) -> dict[str, dict]:
    """Map title -> issue object (chi quan tam title de detect trung)."""
    out = run_gh(["api", f"repos/{repo}/issues?state=all&per_page=100", "--paginate"])
    items = json.loads(out) if out.strip() else []
    # Loc pull request
    items = [i for i in items if "pull_request" not in i]
    return {i["title"]: i for i in items}


def create_milestone(repo: str, title: str, existing: dict, dry_run: bool) -> Optional[int]:
    if title in existing:
        return existing[title]["number"]
    if dry_run:
        print(f"  [dry-run] CREATE milestone: {title}")
        return None
    data = gh_api("POST", f"repos/{repo}/milestones", {"title": title})
    print(f"  + milestone: {title}")
    return data["number"]


def create_issue(repo: str, spec: IssueSpec, milestone_map: dict[str, int],
                 existing_issues: dict, dry_run: bool) -> Optional[dict]:
    if spec.title in existing_issues:
        existing = existing_issues[spec.title]
        spec.gh_number = existing["number"]
        spec.gh_node_id = existing["node_id"]
        return existing

    if dry_run:
        ms = f", milestone={spec.milestone}" if spec.milestone else ""
        print(f"  [dry-run] CREATE issue: {spec.title} [labels={','.join(spec.labels)}{ms}]")
        return None

    payload = {
        "title": spec.title,
        "body": spec.body,
        "labels": spec.labels,
    }
    if spec.milestone and spec.milestone in milestone_map:
        payload["milestone"] = milestone_map[spec.milestone]

    # gh api POST khong support labels la array qua -f, dung --input
    body_json = json.dumps(payload)
    out = run_gh(["api", "-X", "POST", f"repos/{repo}/issues", "--input", "-"],
                 input_data=body_json)
    data = json.loads(out)
    spec.gh_number = data["number"]
    spec.gh_node_id = data["node_id"]
    print(f"  + #{data['number']}: {spec.title}")
    return data


def link_sub_issue(parent_node_id: str, child_node_id: str, dry_run: bool) -> None:
    """Link 1 issue thanh sub-issue cua issue khac qua GraphQL."""
    if dry_run:
        print(f"  [dry-run] LINK sub-issue: {child_node_id} -> parent {parent_node_id}")
        return

    mutation = """
    mutation($parentId: ID!, $childId: ID!) {
      addSubIssue(input: { issueId: $parentId, subIssueId: $childId }) {
        subIssue { id number }
      }
    }
    """
    try:
        result = gh_graphql(mutation, {"parentId": parent_node_id, "childId": child_node_id})
        if "errors" in result:
            msg = result["errors"][0].get("message", "")
            if "already" in msg.lower():
                return
            print(f"  ! GraphQL error: {msg}")
    except RuntimeError as e:
        if "already" in str(e).lower():
            return
        raise


# ---------- Main flow ----------
def main():
    ap = argparse.ArgumentParser(description="Import Jira CSV -> GitHub Issues")
    ap.add_argument("--csv", required=True, help="Duong dan toi file CSV")
    ap.add_argument("--repo", required=True, help="GitHub repo: owner/name")
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--dry-run", action="store_true", help="Xem truoc, khong goi API")
    g.add_argument("--execute", action="store_true", help="Chay that")
    ap.add_argument("--setup-only", action="store_true",
                    help="Chi tao labels + milestones, bo qua issues")
    ap.add_argument("--rate-delay", type=float, default=0.3,
                    help="Delay giua moi API call (giay), default 0.3")
    args = ap.parse_args()

    dry_run = args.dry_run
    csv_path = Path(args.csv).resolve()
    if not csv_path.exists():
        print(f"X File CSV khong ton tai: {csv_path}", file=sys.stderr)
        sys.exit(1)

    print(f"=> Doc CSV: {csv_path}")
    rows = parse_csv(csv_path)
    print(f"   {len(rows)} dong (sau khi bo dong trong)")

    specs = build_issue_specs(rows)
    epics    = [s for s in specs if s.issue_type == "epic"]
    stories  = [s for s in specs if s.issue_type == "story"]
    subtasks = [s for s in specs if s.issue_type == "subtask"]
    print(f"   = {len(epics)} Epic, {len(stories)} Story, {len(subtasks)} Sub-task")

    # Kiem tra gh CLI
    try:
        run_gh(["auth", "status"])
    except RuntimeError as e:
        print(f"X gh CLI chua login: {e}", file=sys.stderr)
        sys.exit(1)

    print(f"\n=> Lay du lieu hien co tu {args.repo}...")
    existing_labels    = get_existing_labels(args.repo) if not dry_run else {}
    existing_milestones= get_existing_milestones(args.repo) if not dry_run else {}
    existing_issues    = get_existing_issues(args.repo) if not dry_run else {}
    print(f"   {len(existing_labels)} labels, {len(existing_milestones)} milestones, "
          f"{len(existing_issues)} issues co san")

    # ---------- 1. Labels ----------
    print(f"\n=> Tao labels...")
    all_label_names = set()
    for s in specs:
        all_label_names.update(s.labels)

    def color_of(label: str) -> str:
        if label in TYPE_COLORS:
            return TYPE_COLORS[label]
        if label.startswith("priority:"):
            prio = label.split(":")[1].capitalize()
            return PRIORITY_COLORS.get(prio, "CCCCCC")
        if label.startswith("sprint:"):
            return SPRINT_COLOR
        return TAG_COLORS.get(label, "CCCCCC")

    for label in sorted(all_label_names):
        get_or_create_label(args.repo, label, color_of(label),
                            existing=existing_labels, dry_run=dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    # ---------- 2. Milestones ----------
    print(f"\n=> Tao milestones (sprints)...")
    sprint_names = sorted({s.milestone for s in specs if s.milestone},
                          key=lambda x: (len(x), x))
    milestone_map: dict[str, int] = {}
    for sp in sprint_names:
        num = create_milestone(args.repo, sp, existing_milestones, dry_run)
        if num:
            milestone_map[sp] = num
        if not dry_run:
            time.sleep(args.rate_delay)

    # Fill milestone_map tu existing
    for sp_title, ms in existing_milestones.items():
        milestone_map.setdefault(sp_title, ms["number"])

    if args.setup_only:
        print("\n=> Setup-only: bo qua tao issues.")
        return

    # ---------- 3. Issues: Epic -> Story -> Sub-task ----------
    csv_id_to_spec: dict[str, IssueSpec] = {s.csv_id: s for s in specs}

    print(f"\n=> Tao Epics ({len(epics)})...")
    for s in epics:
        create_issue(args.repo, s, milestone_map, existing_issues, dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    print(f"\n=> Tao Stories ({len(stories)})...")
    for s in stories:
        create_issue(args.repo, s, milestone_map, existing_issues, dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    print(f"\n=> Tao Sub-tasks ({len(subtasks)})...")
    for s in subtasks:
        create_issue(args.repo, s, milestone_map, existing_issues, dry_run)
        if not dry_run:
            time.sleep(args.rate_delay)

    # ---------- 4. Link sub-issue ----------
    print(f"\n=> Link sub-issue (Story -> Epic, Sub-task -> Story)...")
    linked = 0
    for s in specs:
        if not s.parent_csv_id:
            continue
        parent = csv_id_to_spec.get(s.parent_csv_id)
        if not parent:
            print(f"  ! Khong tim thay parent {s.parent_csv_id} cho {s.csv_id}")
            continue
        if not dry_run and (not s.gh_node_id or not parent.gh_node_id):
            print(f"  ! Thieu node_id cho {s.csv_id} hoac parent {parent.csv_id}")
            continue
        link_sub_issue(
            parent_node_id=parent.gh_node_id or "<parent>",
            child_node_id=s.gh_node_id or "<child>",
            dry_run=dry_run,
        )
        linked += 1
        if not dry_run:
            time.sleep(args.rate_delay)

    print(f"\n=> Xong! Da link {linked} sub-issue.")
    if dry_run:
        print("   (Dry-run: khong co thay doi nao tren GitHub. Chay lai voi --execute de import that)")


if __name__ == "__main__":
    main()
