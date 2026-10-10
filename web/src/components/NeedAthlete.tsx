import { Alert, Button } from "@mui/material";
import { Link as RouterLink } from "react-router";

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
