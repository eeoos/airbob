from pathlib import Path
import tempfile
import unittest

import pr_comment
import pr_report

REPO = 'eeoos/airbob'
HEAD = 'a' * 40
BODY = pr_report.MARKER + '\n## PR 점검 보고서\n'


class FakeGitHub:
    def __init__(self, comments=(), head=HEAD, repo=REPO, state='open'):
        self.pull = {'head': {'sha': head, 'repo': {'full_name': repo} if repo else None}, 'state': state}
        self.comments = list(comments)
        self.calls = []

    def __call__(self, method, url, body=None):
        self.calls.append((method, url.replace(pr_comment.API, ''), body))
        if method == 'GET' and '/pulls/' in url:
            return self.pull
        if method == 'GET' and '/comments?' in url:
            page = int(url.rsplit('page=', 1)[1])
            return self.comments[(page - 1) * 100:page * 100]
        return {}

    def writes(self):
        return [(method, path, body) for method, path, body in self.calls if method != 'GET']


def comment(id, body, login=pr_comment.BOT):
    return {'id': id, 'body': body, 'user': {'login': login}}


class PublishTests(unittest.TestCase):
    def test_creates_the_report_when_the_bot_has_not_commented(self):
        github = FakeGitHub([comment(1, 'LGTM', 'eeoos'), comment(2, pr_report.MARKER + ' copied by a person', 'eeoos')])
        self.assertEqual(pr_comment.publish(github, REPO, 7, HEAD, BODY), 'created the report on PR #7')
        self.assertEqual(github.writes(), [('POST', f'/repos/{REPO}/issues/7/comments', {'body': BODY})])

    def test_updates_the_existing_bot_report_found_on_a_later_page(self):
        comments = [comment(i, f'comment {i}', 'eeoos') for i in range(100)] + [comment(500, pr_report.MARKER + '\nold')]
        github = FakeGitHub(comments)
        self.assertEqual(pr_comment.publish(github, REPO, 7, HEAD, BODY), 'updated the report on PR #7')
        self.assertEqual(github.writes(), [('PATCH', f'/repos/{REPO}/issues/comments/500', {'body': BODY})])

    def test_an_identical_report_is_not_rewritten(self):
        github = FakeGitHub([comment(9, BODY)])
        self.assertEqual(pr_comment.publish(github, REPO, 7, HEAD, BODY), 'the report on PR #7 is already current')
        self.assertEqual(github.writes(), [])

    def test_stale_fork_and_closed_pull_requests_are_skipped(self):
        cases = {'stale': FakeGitHub(head='b' * 40), 'fork': FakeGitHub(repo='someone/airbob'),
                 'deleted fork': FakeGitHub(repo=None), 'closed': FakeGitHub(state='closed')}
        for name, github in cases.items():
            with self.subTest(name=name):
                self.assertTrue(pr_comment.publish(github, REPO, 7, HEAD, BODY).startswith('skipped: '))
                self.assertEqual(github.writes(), [])

    def test_invalid_repository_or_commit_is_rejected_before_any_request(self):
        for repository, head in (('eeoos/airbob/../x', HEAD), (REPO, 'not-a-sha'), (REPO, '0' * 40)):
            github = FakeGitHub()
            with self.subTest(repository=repository, head=head), self.assertRaises(ValueError):
                pr_comment.publish(github, repository, 7, head, BODY)
            self.assertEqual(github.calls, [])


class LoadReportTests(unittest.TestCase):
    def artifact(self, number, body):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        (Path(directory.name) / 'pr-number').write_text(number)
        (Path(directory.name) / 'report.md').write_text(body)
        return directory.name

    def test_reads_the_number_and_report(self):
        self.assertEqual(pr_comment.load_report(self.artifact('42\n', BODY)), (42, BODY))

    def test_untrusted_artifact_content_is_validated(self):
        for number, body in (('0', BODY), ('42; rm -rf /', BODY), ('', BODY), ('42', '## no marker\n')):
            with self.subTest(number=number, body=body[:12]), self.assertRaises(ValueError):
                pr_comment.load_report(self.artifact(number, body))

    def test_oversized_report_is_cut_to_the_comment_limit(self):
        number, body = pr_comment.load_report(self.artifact('42', BODY + 'x' * (pr_report.COMMENT_LIMIT + 10)))
        self.assertLessEqual(len(body), pr_report.COMMENT_LIMIT)
        self.assertTrue(body.startswith(pr_report.MARKER))


if __name__ == '__main__':
    unittest.main()
