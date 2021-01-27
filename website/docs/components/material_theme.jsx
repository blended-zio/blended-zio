import { createMuiTheme } from '@material-ui/core/styles';
import { ThemeProvider } from '@material-ui/styles';
import Button from '@material-ui/core/Button';

export const theme = createMuiTheme({
  palette: {
    primary: {
      main: '#00acd5',
    },
    secondary: {
      main: '#ffe239',
    },
  },
});

export const LinkButton = (title, url) =>
  <ThemeProvider theme={theme}>
    <Button variant="contained" color="primary" onClick={() => { location.href = url }}>title</Button>
  </ThemeProvider>
