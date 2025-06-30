//System commands for calling:

// Check if running in nw.js environment before accessing nw APIs
if (typeof nw !== 'undefined' && nw.App && nw.App.manifest) {
  document.title = nw.App.manifest.window.title;
  nw.Window.get().setMinimumSize(640, 480); // for new windows
}

let losesFocusPause = false;

//nw.Window.get().on with 'focus' and 'blur' are not working properly
//(https://github.com/nwjs/nw.js/issues/7982)
//Need another method.

/*
nw.Window.get().on('focus', function(){
  let needPause = localStorage.getItem('MOD_setPauseWhenWinUnfocused');
  if (losesFocusPause && needPause=="true"){
    CircuitJS1.setSimRunning(true);
    losesFocusPause=false;
    SetBtnsStyle();
  }
})
nw.Window.get().on('blur', function(){
  let needPause = localStorage.getItem('MOD_setPauseWhenWinUnfocused');
  if (needPause=="true"){
    if (CircuitJS1.isRunning()) losesFocusPause=true;
      CircuitJS1.setSimRunning(false);
      SetBtnsStyle();
  }
})
*/

// New method:

function onWinFocus(){
  let needPause = localStorage.getItem('MOD_setPauseWhenWinUnfocused');
  if (losesFocusPause && needPause=="true"){
    if (typeof CircuitJS1 !== 'undefined' && CircuitJS1.setSimRunning) {
      CircuitJS1.setSimRunning(true);
      losesFocusPause=false;
      SetBtnsStyle();
    }
  }
}
function onWinBlur(){
  let needPause = localStorage.getItem('MOD_setPauseWhenWinUnfocused');
  if (needPause=="true"){
    if (typeof CircuitJS1 !== 'undefined' && CircuitJS1.isRunning && CircuitJS1.isRunning()) {
      losesFocusPause=true;
      CircuitJS1.setSimRunning(false);
      SetBtnsStyle();
    }
  }
}
window.addEventListener("focus", onWinFocus);
window.addEventListener("blur", onWinBlur);

//but the blur event is triggered when the iframe is clicked

// For modDialog:

function setScaleUI(){
  try {
    let scale = document.getElementById("scaleUI").value;
    let zoomValue = parseFloat(scale); // Convert to CSS zoom range
    document.body.style.zoom = zoomValue;
    console.log("UI zoom set to:", zoomValue);
  } catch (e) {
    console.warn('Failed to set UI zoom:', e);
  }
}

function getScaleInfo(){
  let scaleString = document.querySelector('.scaleInfo');
  let scale = document.getElementById("scaleUI");
  if (scaleString && scale) {
    scaleString.textContent = parseInt(scale.value*100)+"%";
  }
}

function setAllAbsBtnsTopPos (top) {
  let triggerLabel = document.querySelector(".triggerLabel");
  //let absBtns = document.querySelectorAll(".btn-top-pos");
  if (triggerLabel) {
    triggerLabel.style.top = top;
  }

  //absBtns[0].style.setProperty('top', top, 'important');
  //absBtns[1].style.setProperty('top', top, 'important');
  // it does not work at the first start
  // attempt 2:

  if (document.styleSheets && document.styleSheets[2]) {
    const stylesheet = document.styleSheets[2];
    let absBtns;
    for(let i = 0; i < stylesheet.cssRules.length; i++) {
      if(stylesheet.cssRules[i].selectorText === '.btn-top-pos') {
        absBtns = stylesheet.cssRules[i];
        break;
      }
    }
    if (absBtns) {
      absBtns.style.setProperty('top', top, 'important');
    }
  }
  // It worked!
}


// For Run/Stop and Reset buttons:

function SetBtnsStyle() {

  let absBtnIcon = localStorage.getItem('MOD_absBtnIcon');
  let hideAbsBtns = localStorage.getItem('MOD_hideAbsBtns');

  let RunStopBtn = document.getElementsByClassName("run-stop-btn")[0];
  let ResetBtn = document.getElementsByClassName("reset-btn")[0];

  if (document.getElementById("trigger") && document.getElementById("trigger").checked == true){
    if (RunStopBtn) RunStopBtn.style.display = "none";
    if (ResetBtn) ResetBtn.style.display = "none";
  } else {
    if (hideAbsBtns=="false"){
      setTimeout(() => {
        if (RunStopBtn) RunStopBtn.style.display = "block";
        if (ResetBtn) ResetBtn.style.display = "block";
      }, 1100);
    }
  }

  if (RunStopBtn && typeof CircuitJS1 !== 'undefined' && CircuitJS1.isRunning) {
    if (CircuitJS1.isRunning() == false) {
      RunStopBtn.innerHTML = '&#xE801;'; // \e801
      if(RunStopBtn.classList.contains('modDefaultRunStopBtn')){
        RunStopBtn.style.color = "green";
        RunStopBtn.style.borderColor = "green";
      }
    } else {
      if (absBtnIcon=="pause") RunStopBtn.innerHTML = '&#xE802;';
      else RunStopBtn.innerHTML = '&#xE800;';
      if(RunStopBtn.classList.contains('modDefaultRunStopBtn')){
        RunStopBtn.style.color = "red";
        RunStopBtn.style.borderColor = "red";
      }
    }
  }
}
