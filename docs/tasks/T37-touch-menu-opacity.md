# T37 터치 기기에서 소설 모드 메시지 메뉴가 계속 흐린 문제

- **상태**: DONE
- **웨이브**: 8
- **의존**: T31, T36
- **브랜치**: `task/T37-touch-menu-opacity`
- **마이그레이션**: 없음

## 증상
소설 모드에서 메시지 액션 줄(`⋯`, 재생성, 이어쓰기, `‹ n/m ›`)이 투명도 35%로 흐리다. 마우스를 올리면 진해지지만 **터치 기기에는 hover가 없어서 흐린 상태가 영구적**이고, 핸드폰에서는 버튼을 찾기 어렵다.

## 원인
T31에서 읽는 흐름을 방해하지 않으려고 `opacity-35 hover:opacity-100`을 줬다. **`hover:`가 없는 기기를 고려하지 않았다.**

Tailwind v4의 `hover:` 변형은 이미 `@media (hover: hover)`로 감싸져 있어서 터치 기기에서 발동하지 않는다. 문제는 **기본값인 `opacity-35`가 모든 기기에 적용되는 것**이었다.

## 범위
- `crack-frontend/src/index.css`(`.menu-dim`)
- `crack-frontend/src/components/chat/MessageList.tsx`

## 구현 내용
기본값을 뒤집었다. **선명한 것이 기본이고, 마우스가 있는 기기에서만 흐려진다.**

```css
.menu-dim { opacity: 1; transition: opacity 150ms; }
@media (hover: hover) and (pointer: fine) {
  .menu-dim { opacity: 0.35; }
  .menu-dim:hover, .menu-dim:focus-within { opacity: 1; }
}
```

`MessageList`의 유틸리티 나열을 `menu-dim` 한 클래스로 바꿨다.

## 완료 조건
- [x] 터치 기기에서 액션 줄이 선명하게 보인다
- [x] 마우스가 있는 기기에서는 전과 같이 흐리고 올리면 진해진다
- [x] 키보드 포커스에서도 진해진다(`focus-within`)
- [x] 빌드 산출물에 미디어 쿼리가 들어갔다
- [x] 프론트 build 통과, lint 오류 0

## 작업 로그

### 2026-09-28
T36을 보고할 때 남은 과제로 적어 둔 것을 사용자가 바로 고치라고 했다.

**기본값을 뒤집는 쪽으로 고쳤다.** `opacity-35`를 기본으로 두고 예외를 추가하는 방향은 "hover가 없는 기기"를 계속 특별 취급해야 한다. 선명한 것을 기본으로 두고 **마우스가 있을 때만 흐리게** 하면, 장치 종류를 모르는 상태에서도 항상 쓸 수 있는 쪽이 기본이 된다.

**`(pointer: fine)`을 같이 걸었다.** `(hover: hover)`만 쓰면 스타일러스나 일부 하이브리드 기기가 hover를 보고한다. 정밀 포인터까지 요구하면 마우스·트랙패드에만 적용된다.

**Tailwind 유틸리티 대신 `index.css`의 클래스로 뺐다.** `[@media(hover:hover)]:opacity-35` 같은 임의 변형으로도 되지만, 조건이 두 개고 hover·focus-within까지 붙어서 클래스 문자열이 읽기 어려워진다. `chat-markdown`처럼 이미 CSS로 뺀 선례가 있다.

**확인:** 빌드 산출물에서 `.menu-dim{opacity:1;...}`과 미디어 쿼리 안의 `.menu-dim{opacity:.35}` 둘 다 확인했다. build·lint 통과.
