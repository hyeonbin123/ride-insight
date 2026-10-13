// [파일 역할] 선수를 아직 고르지 않았을 때 보여 주는 안내 상자. 대시보드와 라이딩 목록 화면이 선수 id가
// 없으면 이 컴포넌트를 대신 그린다. 서버 API가 모두 선수 id를 요구하므로, 빈 요청을 보내는 대신 선수 페이지로
// 가는 버튼을 준다.

import { Alert, Button } from "@mui/material";
import { Link as RouterLink } from "react-router";

// Alert의 action 자리에 라우터 링크 버튼('선수 설정', /athlete로 이동)을 둔다.
export function NeedAthlete() {
  return (
    <Alert
      severity="info"
      action={
        <Button component={RouterLink} to="/athlete" color="inherit" size="small">
          선수 설정
        </Button>
      }
    >
      먼저 선수를 만들거나 선택하세요.
    </Alert>
  );
}
