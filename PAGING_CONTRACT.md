# 이번 실행 단위: 과거 cursor + Room Paging + 전경/배경 진입 분리

- 기본 page size 20, 요청 limit 1–50. GET /rooms/demo/messages?limit=20&before=<opaque cursor>. cursor는 방·serverInstanceId·배타적 sequence를 묶는다. 최신 조회는 before 없음. 응답은 메시지 오름차순·nextBefore·endOfHistory·highWatermark·방·서버 실행을 포함한다. 빈 페이지/처음까지 조회하면 nextBefore=null/endOfHistory=true. 다른 방 cursor 400, 이전 서버 실행 cursor 409. 접근 검사는 먼저다.
- 첫 WS 프레임은 전체 snapshot 대신 최신 페이지다. 최신 페이지 생성과 구독 등록을 append와 같은 서버 잠금 안에서 처리한다. 그 시점 이후 append는 live event로 전달한다. Android bootstrap은 이 프레임을 사용하므로 GET→구독 사이의 누락 틈이 없다. 과거 페이지와 live event 중복은 Room 키로 병합한다.
- Room PagingSource의 SQL은 최신부터 읽고 LazyColumn reverseLayout으로 시간 방향을 표시한다. network cursor는 별도 DB 페이지 키다. 과거 끝 근처 또는 명시 버튼이 HTTP page를 가져와 같은 transaction으로 기록+키를 저장한다. 네트워크 결과가 UI 목록을 수정하지 않는다. 실패는 cursor를 전진시키지 않고 수동 retry한다. 이번에는 network/DB cursor를 드러내는 작은 coordinator를 쓰며 RemoteMediator는 추가하지 않는다.
- 캐시 삭제 없음. 계정/방/서버 실행별 키. 과거 페이지 응답이 늦어도 요청 cursor가 현재 키와 같을 때만 전진한다. 새 최신 페이지가 이미 진행한 과거 키를 되돌리지 않는다. 과거 스크롤은 stable message key/offset을 유지하고 자동 최신 이동은 사용자가 최신 끝을 따라갈 때만 한다.
- 전경 소켓은 Application의 ProcessLifecycleOwner로 관리한다. 화면 회전/화면 이동으로 소켓을 닫지 않는다. 배경에서는 소켓을 닫고 Push adapter는 공통 repository/Room에 본문 또는 durable target을 저장하고 catch-up 필요를 반환한다. 로컬 adapter는 실제 FCM 수신이 아니다.
- bounded 최신 페이지는 배경 동안의 모든 누락을 채우지 않는다. before는 과거 탐색, after는 마지막 확인 순서 이후 누락 보충이다. 복귀 catch-up은 [여섯 번째 계약](CATCH_UP_CONTRACT.md)의 별도 Room 연속 cursor와 after 경로로 구현했다. FCM 중복·지연/알림 권한 거절, lifecycle 경합, process kill vs force-stop도 문서화한다. Firebase 계정/프로젝트/키/토큰/지속 권한은 생성하지 않는다.
