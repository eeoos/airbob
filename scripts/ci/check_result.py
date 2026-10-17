"""The required check fails if any selected job failed, was cancelled, or skipped."""
import json
import os


def verify(needs):
    if needs['scope']['result'] != 'success':
        raise ValueError('change selection did not succeed')
    scopes = needs['scope']['outputs']
    for job in ('app', 'infrastructure', 'data', 'api_docs'):
        selected = scopes[job]
        if selected not in ('true', 'false'):
            raise ValueError(f'{job} selection is missing')
        expected = 'success' if selected == 'true' else 'skipped'
        if needs[job]['result'] != expected:
            raise ValueError(f'{job}: expected {expected}, observed {needs[job]["result"]}')


if __name__ == '__main__':
    verify(json.loads(os.environ['CI_NEEDS']))
    print('Every selected CI check passed.')
