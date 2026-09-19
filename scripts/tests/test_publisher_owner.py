"""Synthetic API responses only; these tests never publish or change GitHub permissions."""
from copy import deepcopy
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import publish_github as publisher


class OwnerAuthorizationTests(unittest.TestCase):
    profile = {'login': 'synthetic-publisher', 'id': 42}
    owner = 'Synthetic-Organization'

    def membership(self, **updates):
        member = {'state': 'active', 'role': 'admin', 'user': {'login': self.profile['login']},
                  'organization': {'login': self.owner}}
        member.update(updates)
        return member

    def invoke(self, membership=None, organization=None, repository=None, resume=False):
        responses = [organization if organization is not None else {'login': self.owner},
                     membership if membership is not None else self.membership()]
        if resume:
            responses.append(repository)
        with patch.object(publisher, 'gh_json', side_effect=responses) as api:
            publisher.assert_owner_authorized(self.profile, self.owner,
                resume_target=f'{self.owner}/umbra' if resume else None)
            for call in api.call_args_list:
                self.assertEqual(call.args[:3], ('api', '--hostname', 'github.com'))
                self.assertEqual(len(call.args), 4)  # no write method/body flags
            return api.call_args_list

    def test_org_admin_can_authorize_creation_using_only_get_requests(self):
        calls = self.invoke()
        self.assertEqual(calls[0].args[-1], f'orgs/{self.owner}')
        self.assertEqual(calls[1].args[-1], f'user/memberships/orgs/{self.owner}')

    def test_personal_owner_still_requires_exact_authenticated_login(self):
        with patch.object(publisher, 'gh_json') as api:
            publisher.assert_owner_authorized(self.profile, 'SYNTHETIC-PUBLISHER')
            api.assert_not_called()

    def test_pending_membership_cannot_authorize(self):
        with self.assertRaises(RuntimeError):
            self.invoke(membership=self.membership(state='pending'))

    def test_wrong_membership_user_or_organization_is_rejected(self):
        for field, value in [('user', {'login': 'other-user'}),
                             ('organization', {'login': 'Other-Organization'}),
                             ('user', None), ('organization', 'wrong'), ('role', 'billing_manager')]:
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                self.invoke(membership=self.membership(**{field: value}))

    def test_wrong_org_response_is_rejected(self):
        with self.assertRaises(RuntimeError):
            self.invoke(organization={'login': 'Other-Organization'})

    def test_member_creation_requires_explicit_private_creation_permission(self):
        self.invoke(membership=self.membership(role='member'), organization={
            'login': self.owner, 'members_can_create_private_repositories': True})
        for flag in [False, None, 'true', 1]:
            with self.subTest(flag=flag), self.assertRaises(RuntimeError):
                self.invoke(membership=self.membership(role='member'), organization={
                    'login': self.owner, 'members_can_create_private_repositories': flag})

    def test_resume_requires_push_permission_on_exact_private_repository(self):
        repository = {'full_name': f'{self.owner}/umbra', 'private': True, 'permissions': {'push': True}}
        self.invoke(membership=self.membership(role='member'), repository=repository, resume=True)
        for field, value in [('permissions', {'push': False}), ('permissions', {'push': 'true'}),
                             ('permissions', None), ('full_name', 'other/umbra'), ('private', False)]:
            wrong = deepcopy(repository); wrong[field] = value
            with self.subTest(field=field, value=value), self.assertRaises(RuntimeError):
                self.invoke(repository=wrong, resume=True)

    def test_inaccessible_api_fails_without_changing_permissions(self):
        with patch.object(publisher, 'gh_json', side_effect=RuntimeError('Synthetic denied API')) as api:
            with self.assertRaises(RuntimeError):
                publisher.assert_owner_authorized(self.profile, self.owner)
            api.assert_called_once_with('api', '--hostname', 'github.com', f'orgs/{self.owner}')

    def test_default_owner_matches_current_organization(self):
        self.assertEqual(publisher.DEFAULT_OWNER, 'DevOps-Solutions-IA')


if __name__ == '__main__':
    unittest.main()
