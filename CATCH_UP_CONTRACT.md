# 연속 확인 지점 이후 자동 누락 복구

- 최신 bootstrap은 처음 관찰한 서버 실행의 기준점(baseSequence)을 만든다. 처음 최신 #66–#85를 받으면 base=65, contiguousThrough=85다. #1–#65는 before 과거 탐색 범위다. 최초 기준 이전의 모든 history를 자동 다운로드하지 않는다.
- 같은 실행의 복귀는 저장된 contiguousThrough 이후를 after로 읽는다. 먼저 받은 Push/WS의 높은 sequence 또는 최신 bootstrap의 highWatermark는 contiguousThrough를 건너뛰게 하지 않는다. Room에서 바로 다음 번호가 실제로 있을 때만 연속 지점을 확장한다.
- WS page+subscribe lock의 highWatermark가 이번 복구 target이다. after 위치는 (방, serverInstanceId, sequence)이며 numeric after와 필수 serverInstanceId query로 전달한다. before의 opaque 과거 cursor와 구분한다. GET after 페이지는 target 이하 ascending 20개, nextAfter/endOfCatchUp/throughSequence를 반환한다. 복구 중 새 append는 WS로 저장되며 다음 연속 확장에 합쳐진다.
- 페이지 본문과 연속 cursor는 같은 Room transaction이다. 잘못된 scope/범위/누락/본문 충돌은 rollback한다. 중단되면 commit한 연속 지점부터 재개한다. 늦은 중복 응답은 본문 병합만 하고 경계를 되돌리지 않는다. before 키와 서로 독립이다.
- 계정·방·서버 실행을 저장 키로 분리한다. 계정 전환/배경은 원래 socket+catch-up job을 취소한다. 이미 commit한 원래 계정 결과는 유지하며 새 UI 계정으로 옮기지 않는다. 서버 실행 변경은 새 기준을 만들고 이전 캐시를 보존한다. 메모리 서버에서 사라진 과거 실행의 누락은 복원할 수 없다.
- 로컬/실제 Push hint는 Room에 target을 남긴다. 전경은 같은 coordinator에 합류하고 배경 수신은 짧은 adapter 처리 뒤 sync worker를 예약한다. background worker는 WS를 열지 않는다. 실제 FCM 설정·대상·발송 경로가 결정되기 전 외부 등록/전송하지 않는다.
- 자동 재연결 백오프/서버 영속 DB는 이번 범위가 아니다. 전경 복귀/수동 reconnect로 시작한 세션의 자동 catch-up을 구현한다. 실패 시 기록은 유지하고 복구 실패와 수동 다시 연결을 표시한다.
