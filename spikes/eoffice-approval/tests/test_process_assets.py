import importlib.util
import json
import pathlib
import unittest
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPO = ROOT.parents[1]
BPMN = ROOT / "bpmn" / "eoffice-document-approval.bpmn20.xml"
SEEDS = ROOT / "data" / "demo-documents.json"
POLICY = REPO / "rag-chatbot-fastapi/app/modules/generation/internal/sensitive_policy.py"

spec = importlib.util.spec_from_file_location("sensitive_policy_for_issue7_test", POLICY)
policy = importlib.util.module_from_spec(spec)
assert spec and spec.loader
spec.loader.exec_module(policy)

NS = {"b": "http://www.omg.org/spec/BPMN/20100524/MODEL", "f": "http://flowable.org/bpmn"}

class ProcessAssetTests(unittest.TestCase):
    def test_bpmn_is_well_formed_and_executable(self):
        root = ET.parse(BPMN).getroot()
        process = root.find("b:process", NS)
        self.assertIsNotNone(process)
        self.assertEqual(process.attrib["id"], "eofficeDocumentApproval")
        self.assertEqual(process.attrib["isExecutable"], "true")

    def test_required_role_groups_and_external_topics_exist(self):
        root = ET.parse(BPMN).getroot()
        groups = {node.attrib.get("{http://flowable.org/bpmn}candidateGroups") for node in root.findall(".//b:userTask", NS)}
        self.assertIn("WORKSPACE_ADMIN", groups)
        self.assertIn("ORG_OWNER", groups)
        topics = {node.attrib.get("{http://flowable.org/bpmn}topic") for node in root.findall(".//b:serviceTask", NS)}
        self.assertTrue({"score-sensitivity", "send-reminder", "audit-escalation", "audit-rejection", "publish-document"}.issubset(topics))

    def test_both_demo_branches_match_shared_policy(self):
        docs = json.loads(SEEDS.read_text(encoding="utf-8"))
        self.assertEqual(len(docs), 2)
        by_id = {d["documentId"]: d for d in docs}
        for doc in docs:
            actual = policy.is_sensitive_query(doc["content"]) or doc["target"] in {"shared", "public"}
            self.assertEqual(actual, doc["expectedSensitive"], doc["documentId"])
        self.assertFalse(by_id["demo-ordinary-001"]["expectedSensitive"])
        self.assertTrue(by_id["demo-sensitive-001"]["expectedSensitive"])

    def test_bpmn_has_configurable_timer_durations(self):
        root = ET.parse(BPMN).getroot()
        durations = {n.text for n in root.findall(".//b:timeDuration", NS)}
        self.assertEqual(durations, {"${reminderDuration}", "${escalationDuration}"})

if __name__ == "__main__":
    unittest.main()
