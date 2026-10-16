# Microsoft Sticky Notes 분석 결과 (REA)

대상: `Microsoft.MicrosoftStickyNotes 6.1.4.0 x64` (UWP, .NET Native 2.2 / ilc 컴파일)

## 관찰 (근거 있음)

| 항목 | 내용 | 근거 |
|---|---|---|
| 실행 형태 | `Microsoft.Notes.exe`(23KB 스텁) + `Microsoft.Notes.dll`(35MB 네이티브). CLI 메타데이터 없음 → 디컴파일로 C# 복원 불가 | REA `inspect_managed_artifact` (ev_6c4a2810…, "no CLI data directory"), AppxManifest `ilc.exe` |
| 저장소 | `%LOCALAPPDATA%\Packages\Microsoft.MicrosoftStickyNotes_8wekyb3d8bbwe\LocalState\plum.sqlite` (WAL 모드) | 파일 목록 |
| Note 테이블 | `Text, WindowPosition, IsOpen, IsAlwaysOnTop, Theme, Id, CreatedAt, UpdatedAt, DeletedAt` 외 동기화 컬럼(RemoteId, ChangeKey …) | sqlite_master 스키마 |
| 기타 테이블 | Stroke(펜 잉크), Media(이미지), Insight, Tag, SyncState, UpgradedNote, User | 스키마 |
| 시간값 | .NET Ticks (100ns, 0001-01-01 기준), 예 `638102972868371371` | 컬럼 값 |
| 삭제 | `DeletedAt` 소프트 삭제 | 스키마 |
| 본문 형식 | 문단마다 `\id=<GUID> ` 접두, 서식 토큰 `\strike`/`\strike0`, `\ul0`, `\l`, `\ltrpar`/`\rtlpar` | DB 본문 토큰 집계 + DLL 문자열 |
| 창 위치 | `ManagedPosition=DeviceId:<모니터>;Position=x,y;Size=w,h` (모니터 변경 시 보정 로직 `GetPlacementWithCorrectDeviceId`) | DLL 정규식 문자열 |
| 테마 | Yellow, Green, Pink, Purple, Blue, Gray, Charcoal + `DefaultNoteColor` 설정 | DLL 문자열 |
| 서식 명령 | Bold, Italic, Underline, Strikethrough, ToggleBullets (키보드 단축키 테이블 존재) | DLL 문자열 `BoldCommand` 등 |
| UI 구성 | 노트 창(새 노트 / 메뉴 / 닫기), 메뉴(색 선택, 노트 목록, 노트 삭제), 노트 목록(검색, 노트 셀, 열기/닫기/삭제), 삭제 확인 대화상자(다시 묻지 않음), 설정(라이트/다크, 삭제 확인 토글) | resources.pri 문자열 |

## 추론 / 설계 선택 (새 프로그램)

- 색상 HEX 값은 바이너리에서 문자열로 나오지 않아(컴파일된 XAML) **근사값**으로 지정.
- 단축키 조합(Ctrl+B/I/U/T, Ctrl+Shift+L 등)은 일반적으로 알려진 값 기준 — 바이너리에서 직접 확인하지 않음.
- 클라우드 동기화, 펜 잉크(Stroke), Insight 는 제외.
- 이미지: 원본 Media 테이블(`LocalFileRelativePath`, `LocalState\media` 폴더)처럼 `%APPDATA%\QuickMemo\media\<id>.png` 파일로 두고, 본문에는 `\img=<id>` 토큰으로 위치를 기록 (원본은 이미지를 본문 위 갤러리로 보여 주지만, 여기서는 본문 중간에 넣는 방식).
- 아이콘은 글꼴 글리프 대신 직접 그린 벡터 아이콘 사용 (맑은 고딕에 ⋯ ✕ 등 기호가 없어 깨짐).
- 저장은 SQLite 대신 노트별 텍스트 파일(`%APPDATA%\QuickMemo\notes\<id>.note`), 본문은 원본과 같은 `\id=` + 토큰 방식.

## 미확인

- 실제 서식 토큰 전체 집합(굵게/기울임 토큰 이름) — 사용자 DB에 해당 서식이 없어서 관찰되지 않음.
- 런타임 동작(창 애니메이션 등)은 관찰하지 않음 (정적 분석만 수행).
