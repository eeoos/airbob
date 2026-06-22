-- V8__promote_admin.sql
-- 최초 관리자 프로비저닝. 관리자 API(/api/v1/admin/**)를 쓰려면 ADMIN 계정이 최소 1명 필요하다.
-- 회원가입은 항상 role=MEMBER 로 생성되므로, 지정 이메일 계정을 ADMIN 으로 승격한다.
--
-- ⚠️ 주의 1) 아래 이메일을 운영할 관리자 계정으로 교체할 것.
-- ⚠️ 주의 2) 이 UPDATE 는 "이미 가입된" 계정에만 적용된다(영향 0행이어도 마이그레이션은 성공).
--           새 DB라면 해당 이메일로 먼저 회원가입한 뒤 이 마이그레이션이 실행돼야 승격된다.

UPDATE member
  SET role = 'ADMIN'
  WHERE email = 'admin@airbob.com';
