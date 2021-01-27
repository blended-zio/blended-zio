import React from 'react';
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

export class LinkButton extends React.Component {
  render() {
    return <h1>Hello, wie geht es Dir  !</h1>
  }
}
