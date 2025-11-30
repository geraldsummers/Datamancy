#!/usr/bin/env python3
"""
Interactive review tool for enhanced diagnostic reports.
Displays issues with proposed fixes and allows human approval.
"""
import json
import os
import sys
import glob
from datetime import datetime
from pathlib import Path

PROOFS_DIR = os.environ.get("PROOFS_DIR", os.path.join(os.getcwd(), "volumes/proofs"))


def find_latest_enhanced_report():
    """Find the most recent enhanced diagnostics report."""
    pattern = os.path.join(PROOFS_DIR, "enhanced_diagnostics_*.json")
    files = glob.glob(pattern)
    if not files:
        return None
    return max(files, key=os.path.getmtime)


def display_issue(issue, index):
    """Display a single issue with all details."""
    print(f"\n{'='*80}")
    print(f"Issue #{index + 1}: {issue['service']}")
    print(f"{'='*80}")
    print(f"ID: {issue['id']}")
    print(f"Severity: {issue['severity'].upper()}")
    print(f"Status: {issue['status']}")

    if issue.get('root_cause_hypothesis'):
        print(f"\nRoot Cause Analysis:")
        print(f"  {issue['root_cause_hypothesis']}")

    if issue.get('evidence'):
        print(f"\nEvidence:")
        for ev in issue['evidence']:
            print(f"  - {ev}")

    if issue.get('resource_metrics'):
        print(f"\nResource Metrics:")
        for key, value in issue['resource_metrics'].items():
            print(f"  {key}: {value}")

    if issue.get('log_excerpt'):
        print(f"\nLog Excerpt (last 500 chars):")
        print("  " + "-" * 78)
        for line in issue['log_excerpt'].split('\n')[-20:]:
            print(f"  {line[:78]}")
        print("  " + "-" * 78)

    if issue.get('proposed_fixes'):
        print(f"\nProposed Fixes:")
        for i, fix in enumerate(issue['proposed_fixes'], 1):
            print(f"  {i}. Action: {fix['action']}")
            print(f"     Confidence: {fix['confidence']}")
            print(f"     Reasoning: {fix['reasoning']}")
            if fix.get('parameters'):
                print(f"     Parameters: {fix['parameters']}")
            print()


def review_issue(issue, index, total):
    """Interactively review a single issue and get user decision."""
    display_issue(issue, index)

    print(f"\nReview ({index + 1}/{total}):")
    print("  [a] Approve all proposed fixes")
    print("  [s] Select specific fixes to approve")
    print("  [d] Defer (mark for later review)")
    print("  [i] Ignore this issue")
    print("  [q] Quit review session")

    while True:
        choice = input("\nYour choice: ").strip().lower()

        if choice == 'q':
            return 'quit', None
        elif choice == 'a':
            return 'approved', list(range(len(issue['proposed_fixes'])))
        elif choice == 's':
            return select_fixes(issue)
        elif choice == 'd':
            return 'deferred', None
        elif choice == 'i':
            return 'ignored', None
        else:
            print("Invalid choice. Please try again.")


def select_fixes(issue):
    """Allow user to select specific fixes to approve."""
    fixes = issue['proposed_fixes']
    if not fixes:
        return 'ignored', None

    print("\nSelect fixes to approve (comma-separated numbers, e.g., 1,3):")
    for i, fix in enumerate(fixes, 1):
        print(f"  {i}. {fix['action']} (confidence: {fix['confidence']})")

    while True:
        selection = input("Fixes to approve: ").strip()
        try:
            if not selection:
                return 'ignored', None
            indices = [int(x.strip()) - 1 for x in selection.split(',')]
            # Validate indices
            if all(0 <= i < len(fixes) for i in indices):
                return 'approved', indices
            else:
                print("Invalid fix numbers. Please try again.")
        except ValueError:
            print("Invalid input. Please enter numbers separated by commas.")


def save_approved_actions(report_path, approved_actions):
    """Save approved actions to a JSON file for later execution."""
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    output_file = os.path.join(PROOFS_DIR, f"approved_fixes_{timestamp}.json")

    approval_data = {
        "generated_at": datetime.now().isoformat(),
        "source_report": report_path,
        "approved_actions": approved_actions
    }

    with open(output_file, 'w') as f:
        json.dump(approval_data, f, indent=2)

    print(f"\n✅ Approved actions saved to: {output_file}")
    return output_file


def main():
    # Find report to review
    if len(sys.argv) > 1:
        report_path = sys.argv[1]
    else:
        report_path = find_latest_enhanced_report()

    if not report_path or not os.path.exists(report_path):
        print("❌ No enhanced diagnostic report found.")
        print(f"   Expected location: {PROOFS_DIR}/enhanced_diagnostics_*.json")
        print("\n💡 Run enhanced diagnostics first:")
        print("   curl -X POST http://localhost:8089/analyze-and-propose-fixes")
        sys.exit(1)

    # Load report
    with open(report_path, 'r') as f:
        report = json.load(f)

    # Display summary
    print("\n" + "="*80)
    print("ENHANCED DIAGNOSTICS REVIEW")
    print("="*80)
    print(f"Report: {os.path.basename(report_path)}")
    print(f"Generated: {datetime.fromtimestamp(report['generated_at'] / 1000).strftime('%Y-%m-%d %H:%M:%S')}")
    print(f"\nSummary:")
    for key, value in report['summary'].items():
        print(f"  {key}: {value}")

    issues = report.get('issues', [])
    if not issues:
        print("\n✅ No issues found! All services healthy.")
        return

    print(f"\n📋 Found {len(issues)} issue(s) requiring review.")

    # Sort by severity
    severity_order = {'critical': 0, 'warning': 1, 'info': 2}
    issues.sort(key=lambda x: severity_order.get(x.get('severity', 'info'), 3))

    # Review each issue
    approved_actions = []

    for idx, issue in enumerate(issues):
        decision, fix_indices = review_issue(issue, idx, len(issues))

        if decision == 'quit':
            print("\n⏸️  Review session paused. You can resume later with the same report.")
            break
        elif decision == 'approved' and fix_indices is not None:
            for fix_idx in fix_indices:
                fix = issue['proposed_fixes'][fix_idx]
                approved_actions.append({
                    "issue_id": issue['id'],
                    "service": issue['service'],
                    "action": fix['action'],
                    "confidence": fix['confidence'],
                    "reasoning": fix['reasoning'],
                    "parameters": fix.get('parameters', {})
                })
            print(f"✓ Approved {len(fix_indices)} fix(es) for {issue['service']}")
        elif decision == 'deferred':
            print(f"⏭️  Deferred {issue['service']} for later review")
        elif decision == 'ignored':
            print(f"⏭️  Ignored {issue['service']}")

    # Save approved actions
    if approved_actions:
        output_path = save_approved_actions(report_path, approved_actions)
        print(f"\n📊 Review Summary:")
        print(f"   Total issues reviewed: {len(issues)}")
        print(f"   Actions approved: {len(approved_actions)}")
        print(f"\n🚀 Next steps:")
        print(f"   1. Review the approved actions in: {output_path}")
        print(f"   2. Execute fixes (when implemented):")
        print(f"      ./scripts/execute-approved-fixes.sh {output_path}")
    else:
        print(f"\n📊 Review complete. No actions approved.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⏸️  Review interrupted. You can resume later.")
        sys.exit(0)
